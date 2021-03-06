package tests

import java.nio.file.{Files, Path, Paths}

import com.mle.file.FileUtilities
import com.mle.http.MultipartRequest
import com.mle.util.Util
import com.mle.util.Util._
import org.apache.commons.io.FileUtils
import org.scalatest.FunSuite

/**
 *
 * @author mle
 */
class UploadTests extends FunSuite {
  val fileName = "mpthreetest.mp3"
  val tempFile = FileUtilities.tempDir resolve fileName

  //  test("server plays uploaded track") {
  //    multiPartUpload("http://localhost:9000/playback/uploads")
  //  }
  test("track upload is streamed to player") {
//    multiPartUpload("http://localhost:9000/playback/server")
  }

  def multiPartUpload(uri: String) {
    //    val file = ensureTestMp3Exists()
    val file = Paths get "file here"
    using(new MultipartRequest(uri))(req => {
      req.setAuth("admin", "test")
      req addFile file
      val response = req.execute()
      val statusCode = response.getStatusLine.getStatusCode
      assert(statusCode >= 200 && statusCode < 300)
    })
  }

  def ensureTestMp3Exists(): Path = {
    if (!Files.exists(tempFile)) {
      val resourceURL = Util.resourceOpt(fileName)
      val url = resourceURL.getOrElse(throw new Exception(s"Resource not found: " + fileName))
      FileUtils.copyURLToFile(url, tempFile.toFile)
      if (!Files.exists(tempFile)) {
        throw new Exception(s"Unable to access $tempFile")
      }
    }
    tempFile
  }
}
