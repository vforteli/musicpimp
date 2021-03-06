package com.mle.musicpimp.beam

import com.mle.musicpimp.cloud.PimpMessages.PimpMessage
import play.api.libs.json.Json

/**
 *
 * @author mle
 */
case class BeamCommand(track: String,
                       uri: String,
                       username: String,
                       password: String) extends PimpMessage

object BeamCommand {
  implicit val jsonFormat = Json.format[BeamCommand]
}