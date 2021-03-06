import java.io.File

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.Duration
import scala.io.StdIn
import scala.sys.process._

import util.Try


object Main {

  val config = ConfigFactory.load()                             // load config from application.conf from resources
  val saveDir = config.getString("dir")                   // the dir where to save the files
  val reencodePreset = config.getString("reencodePreset")
  val reencodeCRF = config.getString("reencodeCRF")
  def getTime() = DateTime.now.toIsoDateTimeString()    // temporarily name the file to the time

  def record_stream()={
    recording = true                // set the server state to true
    file_name = getTime()+".mp4"    // set the temp name of the file
    // record the file with streamlink
    process = Process(Seq("streamlink", "-o", file_name, config.getString("stream"), "best"),new File(saveDir)).run
  }

  def stop_recording()={
    recording = false               // change the state of the server
    try{
      process.destroy()             // stop streamlink
    }
    catch {
      case exception: Exception => println(exception.getMessage)
    }
    process = null                  // null process
  }

  def rename(name: String)={

    val name_mp4 = s"$name.mp4"
    reencode(file_name, name_mp4)               // re-encoded the file to fix all the problems with time and corrupt packets
    /*
    if(mv(file_name, name_mp4, saveDir)){
      println(s"file successfully renamed to $name_mp4")
      file_name = ""               // reset file_name
    }
    else{
      println("rename failed")
    }
     */
    /*
    val rename_cmd = Process(Seq("mv", file_name, name+".mp4"),new File(saveDir)).run    // rename file_name to name
     */
  }

  /***
   *
   * @param oldName full file path to old file
   * @param newName full file path to name file name
   * @return boolean - true of rename successful
   */
  def mv(oldName: String, newName: String, dir: String) =
    Try(new File(dir, oldName).renameTo(new File(dir, newName))).getOrElse(false)

  /***
   *
   * @param oldName  should be like "2020-05-10T00:23:19.mp4"
   * @param newName  should be like "nu mai harul.mp4"
   * @description you have to let ffmpeg process the file and rm it because it doesn't like inplace renaming
   */
  def reencode(oldName: String, newName: String)={
    println(s"re-encoding $oldName as $newName...")
    val ffmpeg_cmd = Process(Seq("ffmpeg", "-y", "-i", "file:"+oldName, "-c:v", "libx264", "-preset", reencodePreset,
      "-crf", reencodeCRF, "-c:a", "copy", "file:"+newName), new File(saveDir))     // execute the ffmpeg to re-encode the file
    val rm_cmd = Process(Seq("rm", oldName), new File(saveDir))
    val encode_cmd = ffmpeg_cmd #&& rm_cmd;
    encode_cmd.run    // concurrent, we have to handle other requests
  }

  // global variables to store info about the file we are recording to
  var recording = false         // state info about server
  var process:Process = null    // streamlink process
  var file_name = ""            // temp name of the file we just recorded
  def main(args: Array[String]): Unit = {

    // init Akka actor system and execution context
    implicit val system = ActorSystem("my-system")
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    // main route
    val route =
        get {                     // everything is get to make it easy
          concat(
          path("hello") {
            println("hello request received")
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          },
          path("ls") {
            println("ls request received")
            val p = Process(Seq("ls"), new File(saveDir)).!!
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>$p</h1>"))
          },
          path("pwd") {
            println("pwd request received")
            val p = Process(Seq("pwd"), new File(saveDir)).!!
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>$p</h1>"))
          },
          path("record") {    // start a recording
            println("record request received")
            if (!recording) {
              println("Recording...")
              record_stream()
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Recording...</h1>"))
            }
            else {
              println("Already Recording...")
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Already Recording...</h1>"))
            }
          },
          path("stop") {    // stop the current recording
            println("stop request received")
            if (recording) {
              println("Stopping...")
              stop_recording()
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Stopped...</h1>"))
            }
            else {
              println("Already Stopped...")
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Already Stopped...</h1>"))
            }
          },
          path("rename") {  // change the name of the file we just recorded
            parameter("name".as[String]) { (name) =>
              println(s"rename?=$name request received")
              if (!file_name.equals("")) {
                rename(name)
                complete((StatusCodes.Accepted, s"Name changed to $name"))
              }
              else
                complete((StatusCodes.Accepted, "Name change failed"))
            }
          }
          )
        }

    println(s"Server online at http://odroidxu4:8080/\nPress CTRL-C to stop...")
    // start the server
    val future = for { bindingFuture <- Http().bindAndHandle(route, "0.0.0.0", 8080)
                  waitOnFuture  <- Future.never}
      yield (waitOnFuture,bindingFuture)
    sys.addShutdownHook {                   // terminate any process that's running
      if(recording)
        process.destroy()
    }
    Await.ready(future, Duration.Inf)       // wait forever

    future
      .flatMap(_._2.unbind())               // trigger unbinding from the port
      .onComplete(_ => system.terminate())  // and shutdown when done
  }
}
