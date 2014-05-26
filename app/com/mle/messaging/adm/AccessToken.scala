package com.mle.messaging.adm

import scala.concurrent.duration.Duration
import play.api.libs.json.Json
import com.mle.play.json.JsonFormats2

/**
 * @author Michael
 */
case class AccessToken(access_token: String, expires_in: Duration, scope: String, token_type: String)

object AccessToken {
  implicit val durationFormat = JsonFormats2.durationFormat

  implicit val json = Json.format[AccessToken]
}