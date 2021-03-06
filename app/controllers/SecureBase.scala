package controllers

import java.nio.file.Path

import com.mle.musicpimp.auth.CookieLogin
import com.mle.musicpimp.db.DatabaseUserManager
import com.mle.play.auth.BasicCredentials
import com.mle.play.controllers._
import com.mle.util.Log
import play.api.libs.Files.TemporaryFile
import play.api.libs.{Files => PlayFiles}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/**
 * @author mle
 */
trait SecureBase extends PimpContentController with BaseSecurity with Log {

  val userManager = new DatabaseUserManager

  override def authenticate(implicit request: RequestHeader): Option[AuthResult] =
    super.authenticate orElse CookieLogin.authenticateFromCookie(request)

  /**
   * Validates the supplied credentials.
   *
   * Hashes the supplied credentials and compares the hash with the first line of
   * the password file. If they equal, validation succeeds, otherwise it fails.
   *
   * If the password file doesn't exist, it means no password has ever been set thus
   * the credentials are compared against the default credentials.
   *
   * @param username the supplied username
   * @param password the supplied password
   * @return true if the credentials are valid, false otherwise
   */
  def validateCredentials(username: String, password: String): Boolean =
    userManager.authenticate(username, password)

  override def validateCredentials(creds: BasicCredentials): Boolean =
    validateCredentials(creds.username, creds.password)

  /**
   * Authenticates, logs authenticated request, executes action, in that order.
   *
   * If authentication fails, logs auth fail message.
   */
  def AuthenticatedAndLogged(f: AuthResult => EssentialAction): EssentialAction = Authenticated(user => Logged(user, f))

  /**
   * Returns an action with the result specified in <code>onFail</code> if authentication fails.
   *
   * @param onFail result to return if authentication fails
   * @param f the action we want to do
   */
  def CustomFailingPimpAction(onFail: RequestHeader => Result)(f: AuthResult => Result) =
    Security.Authenticated(req => authenticate(req), req => onFail(req))(user => {
      Logged(user, user => Action(maybeWithCookie(user, f(user))))
    })

  def PimpAction(f: AuthRequest[AnyContent] => Result) = PimpParsedAction(parse.anyContent)(req => f(req))

  def PimpAction(result: => Result) = PimpParsedAction(parse.anyContent)(auth => result)

  def OkPimpAction(f: AuthRequest[AnyContent] => Unit) =
    PimpAction(req => {
      f(req)
      Ok
    })

  def HeadPimpUploadAction(f: OneFileUploadRequest[MultipartFormData[PlayFiles.TemporaryFile]] => Result) =
    PimpUploadAction(req => req.files.headOption
      .map(firstFile => f(new OneFileUploadRequest[MultipartFormData[TemporaryFile]](firstFile, req.user, req)))
      .getOrElse(BadRequest))

  def PimpUploadAction(f: FileUploadRequest[MultipartFormData[PlayFiles.TemporaryFile]] => Result) =
    PimpParsedAction(parse.multipartFormData)(req => {
      val files: Seq[Path] = saveFiles(req)
      f(new FileUploadRequest(files, req.user, req))
    })

  def PimpParsedAction[T](parser: BodyParser[T] = parse.anyContent)(f: AuthRequest[T] => Result) =
    AuthenticatedAndLogged(user => Action(parser)(req => {
      val result = f(new AuthRequest(user.user, req, user.cookie))
      maybeWithCookie(user, result)
    }))

  def PimpActionAsync(f: AuthRequest[AnyContent] => Future[Result]) = PimpParsedActionAsync(parse.anyContent)(f)

  def PimpParsedActionAsync[T](parser: BodyParser[T] = parse.anyContent)(f: AuthRequest[T] => Future[Result]) =
    AuthenticatedAndLogged(auth => Action.async(parser)(req => {
      val resultFuture = f(new AuthRequest(auth.user, req, auth.cookie))
      resultFuture.map(r => maybeWithCookie(auth, r))(ExecutionContext.Implicits.global)
    }))

  /**
   * Due to the "remember me" functionality, browser cookies are updated after a successful cookie-based authentication.
   * To achieve that, we remember the result of the authentication and then update any cookie, if necessary, in the
   * response to the request.
   *
   * @param auth authentication output
   * @param result response
   * @return response, with possibly updated cookies
   */
  private def maybeWithCookie(auth: AuthResult, result: Result): Result =
    auth.cookie.fold(result)(c => {
      log debug s"Sending updated cookie in response..."
      result withCookies c withSession (Security.username -> auth.user)
    })
}