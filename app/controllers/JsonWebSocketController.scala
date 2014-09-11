package controllers

import models.ClientInfo
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.json.JsValue
import play.api.mvc.WebSocket.FrameFormatter
import play.api.mvc.{Call, Controller, RequestHeader}

/**
 * A websockets controller. Subclasses shall implement onConnect, onMessage and onDisconnect.
 *
 * The WebSockets protocol doesn't handle authentication and authorization, so that's taken care of in the subscribe
 * method.
 *
 * @author mle
 */
trait JsonWebSocketController extends WebSocketController2 with Controller with Secured {
  type Message = JsValue
  type Client = ClientInfo[Message]

  /**
   * Opens an authenticated WebSocket connection.
   *
   * This is the controller for requests to ws://... or wss://... URIs.
   *
   * @return a websocket connection using messages of type Message
   * @throws com.mle.musicpimp.exception.PimpException if authentication fails
   */
  def subscribe = ws(FrameFormatter.jsonFrame)

  def subscribeCall: Call

  def wsUrl(implicit request: RequestHeader) =
    subscribeCall.webSocketURL(secure = RequestHelpers.isHttps(request))

  override def newClient(user: String, channel: Channel[Message])(implicit request: RequestHeader) =
    ClientInfo(channel, request, user)
}
