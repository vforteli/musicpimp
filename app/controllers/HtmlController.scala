package controllers

import play.api.mvc.{EssentialAction, RequestHeader}

/**
 *
 * @author mle
 */
trait HtmlController extends Secured {
  protected def navigate(page: => play.twirl.api.Html): EssentialAction = navigate(_ => page)

  protected def navigate(f: RequestHeader => play.twirl.api.Html): EssentialAction =
    PimpAction(implicit req => Ok(f(req)))
}
