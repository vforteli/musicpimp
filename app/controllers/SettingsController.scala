package controllers

import java.nio.file.{Files, Paths}

import com.mle.musicpimp.db.Indexer
import com.mle.musicpimp.library.{Library, Settings}
import com.mle.util.Log
import play.api.data.Form
import play.api.data.Forms._
import views._


/**
 * @author Michael
 */
object SettingsController extends Secured with HtmlController with Log {
  protected val newFolderForm = Form(
    "path" -> nonEmptyText.verifying("Not a directory.", validateDirectory _)
  )

  def manage = navigate(html.musicFolders(Settings.readFolders, newFolderForm))

  def settings = navigate(html.musicFolders(Settings.readFolders, newFolderForm))

  def newFolder = PimpAction(implicit req => {
    newFolderForm.bindFromRequest.fold(
      formWithErrors => {
        log info s"Errors: ${formWithErrors.errors}"
        BadRequest(html.musicFolders(Settings.readFolders, formWithErrors))
      },
      path => {
        Settings.add(Paths get path)
        log info s"Added folder to music library: $path"
        onFoldersChanged()
      }
    )
  })

  def deleteFolder(folder: String) = PimpAction {
    val path = Paths get folder
    Settings delete path
    log info s"Removed folder from music library: $folder"
    onFoldersChanged()
  }

  def validateDirectory(dir: String) = Files.isDirectory(Paths get dir)

  private def onFoldersChanged() = {
    Library.rootFolders = Settings.read
    log info s"Music folders changed, reindexing..."
    Indexer.index()
    Redirect(routes.SettingsController.settings())
  }
}
