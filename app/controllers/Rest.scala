package controllers

import play.api.mvc._
import java.nio.file._
import com.mle.musicpimp.audio._
import com.mle.util.{FileUtilities, Utils, Util, Log}
import com.mle.musicpimp.json.{JsonStrings, JsonMessages}
import play.api.libs.{Files => PlayFiles}
import play.api.libs.json.{Json, JsValue}
import com.mle.audio.meta.{StreamSource, SongTags, SongMeta}
import com.mle.musicpimp.library.{Library, LocalTrack}
import com.mle.http.TrustAllMultipartRequest
import org.apache.http.HttpResponse
import com.mle.play.controllers.{OneFileUploadRequest, AuthRequest, BaseController}
import com.mle.play.streams.{Streams, StreamParsers}
import com.mle.musicpimp.beam.BeamCommand
import scala.concurrent.Future
import java.io._
import com.mle.storage.StorageInt
import com.mle.audio.ExecutionContexts.defaultPlaybackContext
import play.api.mvc.SimpleResult
import play.api.libs.iteratee.Iteratee
import java.net.UnknownHostException

/**
 *
 * @author mle
 */
object Rest
  extends Secured
  with BaseController
  with LibraryController
  with PimpContentController
  with Log {

  def ping = Action(NoCache(Ok))

  def pingAuth = PimpAction(req => NoCacheOk(JsonMessages.Version))

  /**
   * Handles server playback commands POSTed as JSON.
   */
  def playback = JsonAckAction(PlaybackMessageHandler.onJson)

  /**
   * Handles web browser player playback commands POSTed as JSON.
   */
  def webPlayback = JsonAckAction(WebPlayerMessageHandler.onJson)

  /**
   * Alias for `playback`. Should be deprecated.
   */
  def playlist = playback

  def playUploadedFile = UploadedSongAction(MusicPlayer.setPlaylistAndPlay)

  def webPlaylist = PimpAction(req => Ok(Json.toJson(playlistFor(req.user))))

  /**
   * Adds the uploaded track to the server playlist.
   */
  def addUpload = UploadedSongAction(MusicPlayer.playlist.add)

  /**
   * Starts playback of the track in the request.
   *
   * First authenticates, then reads the Track header, then attempts to find the track ID from
   * local storage. If found, starts playback of the local track, otherwise, starts playback of
   * the streamed track available in the body of the request.
   *
   * Returns Unauthorized if authentication fails (as usual), and BadRequest with JSON if the
   * Track header is faulty. Otherwise returns 200 OK.
   *
   * TODO: See and fix https://github.com/playframework/playframework/issues/1842
   */
  def streamedPlayback = Authenticated(user => EssentialAction(requestHeader => {
    val headerValue = requestHeader.headers.get(JsonStrings.TRACK_HEADER)
      .map(Json.parse(_).validate[BaseTrackMeta])
    val metaOrError = headerValue.map(jsonResult => jsonResult.fold(
      invalid => Left(loggedJson(s"Invalid JSON: $invalid")),
      valid => Right(valid))
    ).getOrElse(Left(loggedJson("No Track header is defined.")))
    val authAction = metaOrError.fold(
      error => PimpAction(BadRequest(error)),
      meta => localPlaybackAction(meta.id).getOrElse(streamingAction(meta)))
    authAction(requestHeader)
  }))

  /**
   * Beams the local track according to the [[BeamCommand]] in the request body.
   *
   * TODO: if no root folder for the track is found this shit explodes, fix and return an erroneous HTTP response instead
   */
  def stream = PimpParsedAction(parse.json)(implicit req => {
    Json.fromJson[BeamCommand](req.body).fold(
      invalid = jsonErrors => BadRequest(JsonMessages.InvalidJson),
      valid = cmd => {
        try {
          val (response, duration) = Utils.timed(beam(cmd))
          response.fold(
            errorMsg => BadRequest(JsonMessages.failure(errorMsg)),
            httpResponse => {
              // relays the response code of the request to the beam endpoint to the client
              val statusCode = httpResponse.getStatusLine.getStatusCode
              log info s"Completed track upload in: $duration, relaying response: $statusCode"
              if (statusCode == OK) {
                new Status(statusCode)(JsonMessages.thanks)
              } else {
                new Status(statusCode)
              }
            })
        } catch {
          case uhe: UnknownHostException =>
            NotFound(JsonMessages.failure(s"Unable to find MusicBeamer endpoint. ${uhe.getMessage}"))
          case e: Exception =>
            InternalServerError
        }
      }
    )
  })

  def status = PimpAction(implicit req => pimpResponse(
    html = NoContent,
    json17 = Json.toJson(MusicPlayer.status17),
    latest = Json.toJson(MusicPlayer.status)
  ))

  /**
   * The status of the web player of the user making the request.
   */
  def webStatus = PimpAction(req => Ok(webStatusJson(req.user)))

  private def localPlaybackAction(id: String): Option[EssentialAction] =
    Library.findMetaWithTempFallback(id).map(track => {
      /**
       * The MusicPlayer is intentionally modified outside of the PimpAction block. Here's why this is correct:
       *
       * The request has already been authenticated at this point because this method is called from within an
       * Authenticated block only, see `streamedPlayback`. The following authentication made by PimpAction is thus
       * superfluous. The next line is not inside the OkPimpAction block because we want to start playback before the
       * body of the request, which may contain a large file, has been received: if the track is already available
       * locally, the uploaded file is ignored. Clients should thus ask the server whether it already has a file before
       * initiating long-running, possibly redundant, file uploads.
       */
      MusicPlayer.setPlaylistAndPlay(track)
      log info s"Playing local file of: ${track.id}"
      PimpAction(Ok)
    })

  private def streamingAction(meta: BaseTrackMeta): EssentialAction = {
    val relative = Library.relativePath(meta.id)
    // Saves the streamed media to file if possible
    val fileOpt = Library.suggestAbsolute(relative).filter(canWriteNewFile) orElse
      Option(FileUtilities.tempDir resolve relative).filter(canWriteNewFile)
    val (inStream, iteratee) = fileOpt.fold(Streams.joinedStream())(streamingAndFileWritingIteratee)
    val msg = fileOpt.fold(s"Streaming: $relative")(path => s"Streaming: $relative and saving to: $path")
    log info msg
    // Runs on another thread because setPlaylistAndPlay blocks until the InputStream has
    // enough data. Data will only be made available after this call, when the body of
    // the request is parsed (by this same thread, I guess). This Future will complete
    // when setPlaylistAndPlay returns or when the upload is complete, whichever occurs
    // first. When the OutputStream onto which the InputStream is connected is closed,
    // the Future, if still not completed, will complete exceptionally with an IOException.
    Future {
      val track = meta.buildTrack(inStream)
      MusicPlayer.setPlaylistAndPlay(track)
    }
    PimpParsedAction(StreamParsers.multiPartBodyParser(iteratee))(req => {
      log.info(s"Received stream of track: ${meta.id}")
      Ok
    })
  }

  private def canWriteNewFile(file: Path) =
    try {
      val createdFile = Files.createFile(file)
      Files.delete(createdFile)
      true
    } catch {
      case e: Exception => false
    }

  private def loggedJson(errorMessage: String) = {
    log.warn(errorMessage)
    JsonMessages.failure(errorMessage)
  }

  /**
   * Builds an [[play.api.libs.iteratee.Iteratee]] that writes any consumed bytes to both `file` and a stream. The bytes
   * written to the stream are made available to the returned [[InputStream]].
   *
   * @param file file to write to
   * @return an [[InputStream]] an an [[play.api.libs.iteratee.Iteratee]]
   */
  private def streamingAndFileWritingIteratee(file: Path): (PipedInputStream, Iteratee[Array[Byte], Long]) = {
    Option(file.getParent).foreach(p => Files.createDirectories(p))
    val streamOut = new PipedOutputStream()
    val bufferSize = math.min(10.megs.toBytes.toInt, Int.MaxValue)
    val pipeIn = new PipedInputStream(streamOut, bufferSize)
    val fileOut = new BufferedOutputStream(new FileOutputStream(file.toFile))
    val iteratee = Streams.closingStreamWriter(fileOut, streamOut)
    (pipeIn, iteratee)
  }


  /**
   * Beams a track to a URI as specified in the supplied command details.
   *
   * @param cmd beam details
   */
  private def beam(cmd: BeamCommand): Either[String, HttpResponse] =
    try {
      val uri = cmd.uri
      Util.using(new TrustAllMultipartRequest(uri))(req => {
        req.setAuth(cmd.username, cmd.password)
        Library.findAbsolute(cmd.track).map(file => {
          req addFile file
          val response = req.execute()
          log info s"Uploaded file: $file, bytes: ${Files.size(file)} to: $uri"
          Right(response)
        }).getOrElse(Left(s"Unable to find track with id: ${cmd.track}"))
      })
    } catch {
      case e: Throwable =>
        log.warn("Unable to beam", e)
        Left("An error occurred while MusicBeaming. Please check your settings or try again later.")
    }

  private def webStatusJson(user: String) = {
    val player = WebPlayback.players.get(user) getOrElse new PimpWebPlayer(user)
    Json.toJson(player.status)
  }

  private def playlistFor(user: String): Seq[TrackMeta] =
    WebPlayback.players.get(user).fold(Seq.empty[TrackMeta])(_.playlist.songList)

  private def AckPimpAction[T](parser: BodyParser[T])(bodyHandler: AuthRequest[T] => Unit): EssentialAction =
    PimpParsedAction(parser)(implicit request => {
      try {
        bodyHandler(request)
        AckResponse
      } catch {
        case iae: IllegalArgumentException =>
          log error("Illegal argument", iae)
          val errorMessage = JsonMessages.failure(iae.getMessage)
          BadRequest(errorMessage)
        case t: Throwable =>
          log error("Unable to execute action", t)
          InternalServerError
      }
    })

  private def JsonAckAction(jsonHandler: AuthRequest[JsValue] => Unit): EssentialAction =
    AckPimpAction(parse.json)(jsonHandler)

  private def UploadedSongAction(songAction: PlayableTrack => Unit) =
    MetaUploadAction(implicit req => {
      songAction(req.track)
      AckResponse
    })

  private def MetaUploadAction(f: TrackUploadRequest[MultipartFormData[PlayFiles.TemporaryFile]] => SimpleResult) =
    HeadPimpUploadAction(request => {
      val parameters = request.body.asFormUrlEncoded
      def firstValue(key: String) = parameters.get(key).flatMap(_.headOption)
      val pathParameterOpt = firstValue("path")
      // if a "path" parameter is specified, attempts to move the uploaded file to that library path
      val absolutePathOpt = pathParameterOpt.flatMap(Library.suggestAbsolute).filter(!Files.exists(_))
      absolutePathOpt.flatMap(p => Option(p.getParent).map(Files.createDirectories(_)))
      val requestFile = request.file
      val file = absolutePathOpt.fold(requestFile)(dest => Files.move(requestFile, dest, StandardCopyOption.REPLACE_EXISTING))
      // attempts to read metadata from file if it was moved to the library, falls back to parameters set in upload
      val trackInfoFromFileOpt = absolutePathOpt.flatMap(_ => pathParameterOpt.flatMap(Library.findMeta))
      def trackInfoFromUpload: LocalTrack = {
        val title = firstValue("title")
        val album = firstValue("album") getOrElse ""
        val artist = firstValue("artist") getOrElse ""
        val meta = SongMeta(StreamSource.fromFile(file), SongTags(title.getOrElse(file.getFileName.toString), album, artist))
        new LocalTrack("", meta)
      }
      val track = trackInfoFromFileOpt getOrElse trackInfoFromUpload
      val user = request.user
      val mediaInfo = track.meta.media
      val fileSize = mediaInfo.size
      log info s"User: ${request.user} from: ${request.remoteAddress} uploaded $fileSize"
      f(new TrackUploadRequest(track, file, user, request))
    })

  class TrackUploadRequest[A](val track: LocalTrack, file: Path, user: String, request: Request[A])
    extends OneFileUploadRequest(file, user, request)

}