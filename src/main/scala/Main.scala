import java.io._
import play.api._
import play.api.libs.Codecs._
import play.api.libs.Collections
import scala.io.Source
import scala.util.control.NonFatal
import play.core.HandleWebCommandSupport
import play.api.libs.json._
import play.api.libs.Files
import org.slf4j.LoggerFactory
import java.nio.file.Files

import javax.inject.Inject

class Main(config: String, evolutions: String) {

}

object Main extends App {
  val config = args(0)
  val evolutions = args(1)
  println(config + " " + evolutions)
  println("This")
  println("That")
}

