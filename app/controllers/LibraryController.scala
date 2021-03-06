package controllers

import java.net.URLDecoder
import java.nio.file.Paths

import com.mle.musicpimp.json.JsonMessages
import com.mle.musicpimp.library.{Library, MusicFolder}
import com.mle.util.Log
import models.MusicColumn
import play.api.libs.json.Json
import play.api.mvc._
import views.html

/**
 * @author Michael
 */
trait LibraryController extends Secured with Log {
  def rootLibrary = PimpAction(implicit request => folderResult(Library.rootFolder))

  /**
   * @return an action that provides the contents of the library with the supplied id
   */
  def library(folderId: String) = PimpAction(implicit request => {
    //    val (result, duration) = Utils.timed(Library.folder(folderId).fold(folderNotFound(folderId))(items => folderResult(items)))
    //    log info s"Loaded $folderId in $duration"
    //    result
    Library.folder(folderId).fold(folderNotFound(folderId))(items => folderResult(items))
  })

  private def folderNotFound(id: String)(implicit request: RequestHeader): Result = pimpResult(
    html = NotFound,
    json = NotFound(JsonMessages.failure(s"Unknown folder ID: $id"))
  )

  private def folderResult(collection: => MusicFolder)(implicit request: RequestHeader): Result = {
    respond(
      html = Website.toHtml(collection),
      json = Json.toJson(collection)
    )
  }

  /**
   * Serves the given track and sets the ACCEPT_RANGES header in the response.
   *
   * The Windows Phone background downloader requires the ACCEPT_RANGES header for files over 5 MB. To watch it fail,
   * use a real device not an emulator.
   *
   * @param trackId track to serve
   */
  def download(trackId: String): EssentialAction = download(trackId, _.withHeaders(ACCEPT_RANGES -> "bytes"))

  /**
   * Serves the given track but does NOT set the ACCEPT_RANGES header in the response.
   *
   * The Windows Phone background audio player fails to work properly if the ACCEPT_RANGES header is set.
   *
   * @param trackId track to serve
   */
  def supplyForPlayback(trackId: String) = download(trackId, r => r)

  /**
   * Responds with the song with the given ID.
   *
   * Note: If an unauthorized request is made here, the result is always
   * Unauthorized with JSON content. This differs from the default of
   * redirecting to the login page if the client accepts HTML, because
   * the Background Transfer Service in WP8 makes download requests
   * accepting any response format, yet we want to respond with an
   * Unauthorized as opposed to a redirect to make it easier to deal
   * with download errors on the client side.
   *
   * @param trackId track to download
   */
  def download(trackId: String, f: Result => Result): EssentialAction =
    CustomFailingPimpAction(onDownloadAuthFail)(implicit req => {
      Library.findAbsolute(URLDecoder.decode(trackId, "UTF-8"))
        .fold(NotFound(JsonMessages.failure(s"Unable to find track with ID: $trackId")))(path => {
        f(Ok.sendFile(path.toFile))
      })
    })

  def onDownloadAuthFail(req: RequestHeader): Result = {
    logUnauthorized(req)
    Unauthorized
  }
  def meta(id: String) = PimpAction {
    trackMetaJson(id).fold(trackNotFound(id))(json => Ok(json))
  }

  private def trackNotFound(id: String) = BadRequest(noTrackJson(id))

  def findMeta(id: String) = trackMetaJson(id) getOrElse noTrackJson(id)

  def trackMetaJson(id: String) = Library.findMeta(id).map(Json.toJson(_))

  def noTrackJson(id: String) = JsonMessages.failure(s"Track not found: $id")

  def toHtml(folder: MusicFolder): play.twirl.api.Html = {
    val (col1, col2, col3) = columnify(folder) match {
      case Nil => (MusicColumn.empty, MusicColumn.empty, MusicColumn.empty)
      case h :: Nil => (h, MusicColumn.empty, MusicColumn.empty)
      case f :: s :: Nil => (f, s, MusicColumn.empty)
      case f :: s :: t :: tail => (f, s, t)
    }
    html.library(Paths get folder.folder.path, col1, col2, col3)
  }

  /**
   * Arranges a music collection into columns.
   *
   * TODO: It could be interesting to explore a type like a non-empty list. Scalaz might have something.
   *
   * @param col music collection
   * @param minCount minimum amount of items; if there are less items, only one column is used
   * @param columns column count
   * @return at least one column
   */
  private def columnify(col: MusicFolder, minCount: Int = 20, columns: Int = 3): List[MusicColumn] = {
    val tracks = col.tracks
    val folders = col.folders
    val tracksCount = tracks.size
    val foldersCount = folders.size
    val itemsCount = tracksCount + foldersCount
    if (itemsCount < minCount || columns == 1) {
      List(MusicColumn(folders, tracks))
    } else {
      val cutoff = itemsCount / columns + 1
      val takeColumns = math.min(foldersCount, cutoff)
      val takeTracks = math.max(0, cutoff - foldersCount)
      val column = MusicColumn(folders take takeColumns, tracks take takeTracks)
      val remains = col.copy(folders = folders drop takeColumns, tracks = tracks drop takeTracks)
      column :: columnify(remains, 0, columns - 1)
    }
  }
}
