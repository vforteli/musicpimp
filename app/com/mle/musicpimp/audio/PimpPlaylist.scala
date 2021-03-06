package com.mle.musicpimp.audio

import com.mle.musicpimp.json.JsonMessages
import com.mle.util.Log
import controllers.ServerWS
import play.api.libs.json.JsValue

import scala.collection.mutable

/**
 * @author Michael
 */
class PimpPlaylist extends BasePlaylist[PlayableTrack] with Log {
  // TODO scala-stm
  val songs = mutable.Buffer.empty[PlayableTrack]

  protected override def onPlaylistIndexChanged(idx: Int) =
    send(JsonMessages.playlistIndexChanged(idx))

  protected override def onPlaylistModified(tracks: Seq[PlayableTrack]) =
    send(JsonMessages.playlistModified(tracks))

  def send(json: JsValue) = ServerWS.broadcast(json)
}
