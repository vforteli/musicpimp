package com.mle.musicpimp.library

import java.nio.file.{Files, Path, Paths}

import com.mle.file.FileUtilities
import com.mle.util.Log
import play.api.libs.json.Json

/**
 * @author Michael
 */
trait Settings extends Log {
  val settingsFile = FileUtilities pathTo "settings.json"
  val FOLDERS = "folders"

  def readFolders: Seq[String] = read.map(_.toString)

  def read: Seq[Path] = {
    if (Files.exists(settingsFile)) {
      val jsonString = FileUtilities.readerFrom(settingsFile)(_.mkString(FileUtilities.lineSep))
      val json = Json parse jsonString
      log debug s"Reading: $jsonString"
      val pathStrings = (json \ FOLDERS).as[Seq[String]]
      pathStrings map (Paths.get(_))
    } else {
      Nil
    }
  }

  def save(folders: Seq[Path]) {
    val pathStrings = folders map (_.toAbsolutePath.toString)
    val json = Json toJson Map(FOLDERS -> pathStrings)
    val jsonString = Json stringify json
    log debug s"Saving: $jsonString"
    FileUtilities.writerTo(settingsFile)(_.println(jsonString))
  }

  def add(folder: Path) {
    save(folder +: read)
  }

  def delete(folder: Path) {
    save(read.filter(_ != folder))
  }
}

object Settings extends Settings
