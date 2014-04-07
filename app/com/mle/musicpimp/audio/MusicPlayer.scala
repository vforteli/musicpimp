package com.mle.musicpimp.audio

import com.mle.audio._
import com.mle.musicpimp.actor.ServerPlayerManager
import com.mle.musicpimp.actor.Messages.Restart
import com.mle.util.Log
import scala.Some
import scala.concurrent.duration.Duration
import com.mle.musicpimp.json.{JsonSendah, JsonMessages}
import scala.util.{Success, Failure, Try}
import com.mle.actor.Messages.Stop
import java.io.IOException
import com.mle.musicpimp.library.Library

/**
 * This is a mutable mess. It should be rewritten, maybe using Rx.
 *
 * @author Michael
 */
object MusicPlayer
  extends IPlayer
  with PlaylistSupport[PlayableTrack]
  with JsonSendah
  with Log {

  private val defaultVolume = 40
  val playlist: PimpPlaylist = new PimpPlaylist

  // TODO: jesus fix this
  var errorOpt: Option[Throwable] = None

  /**
   * Every time the track changes, a new [[PimpPlayer]] is used.
   * - Well, why?
   */
  private var player: Option[PimpPlayer] = None

  def underLying = player

  def reset(track: PlayableTrack): Unit = {
    playlist set track
    playlist.current.foreach(playTrack)
  }

  def setPlaylistAndPlay(track: PlayableTrack): Unit = {
    playlist set track
    playTrack(track)
  }

  override def playTrack(songMeta: PlayableTrack): Unit = {
    val initResult = tryInitTrackWithFallback(songMeta)
    initResult match {
      case Success(_) =>
        play()
      case Failure(t) =>
        log.warn(s"Unable to play track: ${songMeta.id}", t)
        errorOpt = Some(t)
    }
  }

  def tryInitTrackWithFallback(track: PlayableTrack): Try[Unit] = {
    errorOpt = None
    Try(initTrack(track)).recoverWith {
      case ioe: IOException if Option(ioe.getMessage).exists(_.startsWith("Pipe closed")) =>
        val id = track.id
        log.warn(s"Unable to initialize track: $id. The stream is closed. Trying to reinitialize.")
        Library.findMetaWithTempFallback(id)
          .map(newTrack => Try(initTrack(newTrack)))
          .getOrElse(Failure(ioe))
    }
  }


  /**
   * Blocks until an [[javax.sound.sampled.AudioInputStream]] can be created of the media.
   *
   * @param track
   * @throws LineUnavailableException
   */
  private def initTrack(track: PlayableTrack): Unit = {
    // If the player exists, tries to obtain the volume; if it fails, falls back to the cached volume.
    val previousVolume: Option[Int] = tryWithFallback(_.volume, _.cachedVolume)
    val previousMute: Option[Boolean] = tryWithFallback(_.mute, _.cachedMute)
    close()
    player = None
    //    log.info(s"Closed track, now initializing: ${track.title}")
    // PimpJavaSoundPlayer.ctor throws at least LineUnavailableException if the audio device cannot be initialized
    player = Some(track.buildPlayer())

    // Maintains the gain & mute status as they were in the previous track.
    // If there was no previous gain, there was no previous track, so we set the default volume.
    val volumeChanged = setVolume(previousVolume getOrElse defaultVolume)
    // ensures the volume message is always sent
    if (!volumeChanged) {
      sendCurrentVolume()
    }
    previousMute foreach mute

    send(JsonMessages.trackChanged(track))
  }

  /**
   * If the player exists, first tries `first`, and if that fails exceptionally, falls back to `fallback`.
   *
   * @param first first attempt
   * @param fallback optional fallback value
   * @tparam T desired result
   * @return a result wrapped in an [[Option]]
   */
  private def tryWithFallback[T](first: PimpPlayer => T, fallback: PimpPlayer => Option[T]): Option[T] =
    player.map(p => Try(first(p)).toOption.orElse(fallback(p))).flatten

  def play() {
    val mustReinitializePlayer = player.exists(_.state == PlayerStates.Closed)
    if (mustReinitializePlayer) {
      player = player.map(p => p.track.buildPlayer())
    }
    player.foreach(p => {
      p.play()
      send(JsonMessages.playStateChanged(PlayerStates.Started))
      ServerPlayerManager.playbackPoller ! Restart
    })
  }

  def stop() {
    player.foreach(p => {
      p.stop()
      send(JsonMessages.playStateChanged(PlayerStates.Stopped))
    })
    ServerPlayerManager.playbackPoller ! Stop
  }

  def seek(pos: Duration) {
    Try {
      player.filter(_.position.toSeconds != pos.toSeconds).foreach(p => {
        p.seek(pos)
        send(JsonMessages.timeUpdated(pos))
      })
    }.recover {
      case ioe: IOException if ioe.getMessage == "Resetting to invalid mark" =>
        log.warn(s"Failed to seek to: $pos. Unable to reset stream.")
    }
  }

  def volume(level: Int): Unit = setVolume(level)

  def volume: Option[Int] = player.map(_.volume)

  /**
   *
   * @param level new volume
   * @return true if the volume was changed, false otherwise
   */
  def setVolume(level: Int): Boolean =
    player.filter(_.volume != level).map(p => {
      p.volume = level
      sendVolumeChanged(level)
    }).isDefined

  def sendVolumeChanged(level: Int) = send(JsonMessages.volumeChanged(level))

  def sendCurrentVolume() = sendVolumeChanged(player.map(_.volume) getOrElse 0)

  def mute(mute: Boolean) {
    player.filter(_.mute != mute).foreach(p => {
      p.mute(mute)
      send(JsonMessages.muteToggled(mute))
    })
  }

  def toggleMute() {
    player.foreach(p => {
      p.toggleMute()
      send(JsonMessages.muteToggled(p.mute))
    })
  }

  def close(): Unit =
    player.foreach(p => {
      p.close()
      p.media.stream.close()
    })

  def position =
    player.map(_.position).getOrElse {
      log.debug(s"Unable to obtain position because no player is initialized, defaulting to 0.")
      Duration.fromNanos(0)
    }

  def status: StatusEvent = player.map(p => StatusEvent(
    p.track,
    p.state,
    p.position,
    p.volume,
    p.mute,
    playlist.songList,
    playlist.index
  )).getOrElse(StatusEvent.empty)

  def status17: StatusEvent17 = {
    player.map(p => {
      val meta = p.track
      StatusEvent17(
        id = p.track.id,
        title = meta.title,
        artist = meta.artist,
        album = meta.album,
        state = p.state,
        position = p.position,
        duration = p.duration,
        gain = 1.0f * p.volume / 100,
        mute = p.mute,
        playlist = playlist.songList,
        index = playlist.index
      )
    }).getOrElse(StatusEvent17.noServerTrackEvent)
  }
}

