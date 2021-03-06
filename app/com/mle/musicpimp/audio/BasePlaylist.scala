package com.mle.musicpimp.audio

import com.mle.audio.IPlaylist
import com.mle.util.Log

import scala.collection.mutable

/**
 *
 * @tparam T type of playlist item
 */
trait BasePlaylist[T]
  extends IPlaylist[T]
  with Log {
  val NO_POSITION = -1
  private var pos = NO_POSITION

  def songs: mutable.Buffer[T]

  def songList = songs.toList

  /**
   * The playlist index. An empty playlist implies index == NO_POSITION,
   * but index == NO_POSITION does not imply an empty playlist.
   *
   * @return the playlist index, or NO_POSITION if no playlist item is selected or the playlist is empty
   */
  def index = pos

  def index_=(newPlaylistPosition: Int) {
    require(newPlaylistPosition >= 0, s"Negative playlist position: $newPlaylistPosition")
    val songCount = songs.size
    require(newPlaylistPosition < songCount, s"No song at index: $newPlaylistPosition, playlist only contains $songCount tracks")
    val changed = pos != newPlaylistPosition
    pos = newPlaylistPosition
    if (changed) {
      onPlaylistIndexChanged(index)
    }
  }

  def current: Option[T] =
    if (index >= 0 && songList.size > index) {
      Some(songs(index))
    } else {
      None
    }

  def next: Option[T] =
    if (songs.size > pos + 1) {
      pos += 1
      onPlaylistIndexChanged(index)
      Some(songs(pos))
    } else {
      None
    }

  def prev: Option[T] =
    if (pos > 0 && songs.size > pos - 1) {
      pos -= 1
      onPlaylistIndexChanged(index)
      Some(songs(pos))
    } else {
      None
    }

  def add(song: T): Unit = {
    songs += song
    onPlaylistModified(songList)
  }

  def delete(position: Int) {
    songs remove position
    onPlaylistModified(songList)
    if (position <= pos && pos >= 0) {
      pos -= 1
      onPlaylistIndexChanged(index)
    }
  }

  private def clearButDontTell() {
    songs.clear()
    pos = NO_POSITION
  }

  def clear() {
    clearButDontTell()
    onPlaylistModified(songList)
    onPlaylistIndexChanged(index)
  }

  def set(song: T) {
    clearButDontTell()
    add(song)
    index = 0
    log.info(s"Playlist set to: $song")
  }

  protected def onPlaylistIndexChanged(idx: Int) {

  }

  protected def onPlaylistModified(songs: Seq[T]) {

  }

}
