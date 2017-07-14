package org.gerweck.scala.util.stream

import scala.collection.immutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

import java.io._
import java.nio.file._
import java.time.Instant
import java.util.zip._

import akka.{ Done, NotUsed }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString

import org.log4s._

import org.gerweck.scala.util.io._
import org.gerweck.scala.util.stream.impl._

/** Provider of streams that take in zip entries and produces zipped data as output.
  *
  * This offers a number of methods that will create
  * [[http://doc.akka.io/docs/akka/current/scala/stream/ Akka Streams]] stages that can be used
  * to write zip archives. To write to your zip, you will send in a stream of [[ZipStream.Entry]]
  * objects, which are an abstraction of a single entry in a zip archive.
  *
  * The structure of a zip archive requires that a single entry is written to completion once
  * it is started, so you may wish to organize the incoming stream so that it does not emit an
  * `Entry` until its data is available.
  *
  * For example, if the contents of a file are generated by a database query, you may wish to have
  * the completion of the query produce the `Entry` object rather than immediately producing an
  * entry that then executes a query to fetch the required data.
  *
  * @author Sarah Gerweck <sarah.a180@gmail.com>
  */
object ZipStream {
  private[this] val logger = getLogger

  /** The maximum amount of time to allow the zip's write to block. This is an internal operation
    * in a dedicated thread pool, so we're setting it to be effectively infinite. */
  private[this] val outputTimeout = 4.hours

  /** The default buffer size when outputting zipped data as a stream. */
  val defaultFlowBuffer: Option[Int] = Some(8 * 1024)
  /** The default buffer size when writing zipped data to a file. */
  val defaultFileBuffer: Option[Int] = Some(8 * 1024)

  /** A source of data that can be included in a stream that is constructing zipped data. These
    * provide a stream and thus they are '''not''' serializable.
    */
  sealed trait Zippable {
    protected[ZipStream] def toActionSource: Source[ZipAction, NotUsed]
  }

  object EntryStorage {
    def unapply(n: Int): Option[EntryStorage] = {
      n match {
        case ZipEntry.STORED   => Some(StoreEntry)
        case ZipEntry.DEFLATED => Some(DeflateEntry)
        case _                 => None
      }
    }
  }
  sealed trait EntryStorage {
    private[stream] def numeric: Int
  }
  case object StoreEntry extends EntryStorage {
    override private[stream] final val numeric = ZipEntry.STORED
  }
  case object DeflateEntry extends EntryStorage {
    override private[stream] final val numeric = ZipEntry.DEFLATED
  }

  /** The metadata associated with a single entry in a zip file. */
  case class EntryMetadata(
    name: String,
    creation: Option[Instant] = None,
    lastAccess: Option[Instant] = None,
    lastModified: Option[Instant] = None,
    comment: Option[String] = None,
    extra: Option[Array[Byte]] = None
  )

  object EntryMetadata {
    import language.implicitConversions
    implicit def nameToMetadata(name: String): EntryMetadata = EntryMetadata(name)
  }

  /** An entry to be written into the zip file.
    *
    * @note These are NOT serializable, as they contain a stream `Source`.
    *
    * @param name The name of the file within the archive. If your entry is meant to be in a
    * folder, put the entire path into this field, separated by slashes. (Zip archives doesn't
    * actually have the concept of a folder: they embed any nesting into the entry's name.)
    *
    * @param data the data that you wish to write into this entry. This entire source must be
    * exhausted before the next entry can be started, so a stall here will hold up the entire
    * stream. Similarly, a failure in this stream will fail the entire archive.
    */
  final class Entry(val metadata: EntryMetadata, data: Source[ByteString, _]) extends Zippable {
    protected[ZipStream] lazy val toActionSource: Source[ZipAction, NotUsed] = {
      Source.single(ZipAction.NewEntry(metadata)) ++
      data.map(ZipAction.Data) ++
      Source.single(ZipAction.CloseEntry)
    }
  }

  final class ExistingZip private (transform: PartialFunction[EntryMetadata, EntryMetadata], source: Source[ZipAction, _]) extends Zippable {
    protected[ZipStream] lazy val toActionSource: Source[ZipAction, NotUsed] = {
      source .statefulMapConcat[ZipAction] { () =>
        import ZipAction._
        var shouldDrop = true
        locally[ZipAction => immutable.Seq[ZipAction]] {
          case NewEntry(name) =>
            if (transform.isDefinedAt(name)) {
              shouldDrop = false
              NewEntry(transform(name)) :: Nil
            } else {
              shouldDrop = true
              Nil
            }
          case other =>
            if (shouldDrop) {
              Nil
            } else {
              other :: Nil
            }
        }
      }.mapMaterializedValue(Function.const(NotUsed))
    }
  }

  object ExistingZip {
    def transform(data: Source[ByteString, _], buffer: Option[Int] = defaultFileBuffer)(transform: PartialFunction[EntryMetadata, EntryMetadata])(implicit ec: ExecutionContext): ExistingZip = {
      val s = data.via(zipInput(outputTimeout, buffer))
      new ExistingZip(transform, s)
    }
    def unchanged(data: Source[ByteString, _], buffer: Option[Int] = defaultFileBuffer)(implicit ec: ExecutionContext): ExistingZip = {
      transform(data, buffer){ case x => x }
    }
  }

  /** A zip compressor that takes in entries and flows out bytes.
    *
    * @param buffer the size (in bytes) of an optional buffer that will hold the zipped data
    * before it is converted into `ByteString` objects. Using a buffer can substantially improve
    * performance, as otherwise you may have many stream elements with just a few bytes in them.
    *
    * @param level the compression level to use when generating the zip. Use `None` to get the
    * default compression, which is generally a good compromise.
    *
    * @param ec the execution context to use for any callback operations. This context will
    * ''not'' be used for any long-running or blocking operations.
    */
  def toStream(buffer: Option[Int] = defaultFlowBuffer, level: Option[Int] = None)(implicit ec: ExecutionContext): Flow[Zippable, ByteString, Future[IOResult]] = {
    entryToActionFlow
      .viaMat(actionToBytesFlow(outputTimeout, buffer, level))(Keep.right)
  }

  /** A zip compressor that takes in entries and writes them to a file.
    *
    * @param path the location of the file to be written.
    *
    * @param existingFile what to do if there is already a file at the provided path.
    *
    * @param buffer the size (in bytes) of an optional buffer that will hold the zipped data
    * before it is written to the file. The OS probably offers output buffering, but this can
    * potentially reduce the number of OS-level writes that need to be made.
    *
    * @param level the compression level to use when generating the zip. Use `None` to get the
    * default compression, which is generally a good compromise.
    *
    * @param ec the execution context to use for any callback operations. This context will
    * ''not'' be used for any long-running or blocking operations.
    */
  def toFile(path: Path, existingFile: ExistingFile = ExistingFile.Fail, buffer: Option[Int] = defaultFileBuffer, level: Option[Int] = None)(implicit ec: ExecutionContext): Sink[Zippable, Future[IOResult]] = {
    val openOpts = StandardOpenOption.WRITE +: existingFile.openOpts
    val os = { () =>
      addOutputBuffer(buffer) {
        Files.newOutputStream(path, openOpts: _*)
      }
    }
    val outSink: Sink[ZipAction, Future[IOResult]] = ZipOutputSink.simple(os, level)(ec)

    entryToActionFlow
      .toMat(outSink)(Keep.right)
  }

  /* INTERNALS */

  private[this] val entryToActionFlow = {
    Flow[Zippable].flatMapConcat(_.toActionSource)
  }

  private[this] def addOutputBuffer(buffer: Option[Int])(os: OutputStream): OutputStream = {
    buffer match {
      case Some(i) => new BufferedOutputStream(os, i)
      case None    => os
    }
  }

  private[this] def addInputBuffer(buffer: Option[Int])(is: InputStream): InputStream = {
    buffer match {
      case Some(i) => new BufferedInputStream(is, i)
      case None    => is
    }
  }

  private[this] def zipInput(blockTimeout: FiniteDuration, buffer: Option[Int])(implicit ec: ExecutionContext): Flow[ByteString, ZipAction, Future[IOResult]] = {
    Flow.fromGraph {
      val bytesToIS = StreamConverters.asInputStream(blockTimeout)
      val zipInputSource = new ZipInputSource(ec)()

      GraphDSL.create(bytesToIS, zipInputSource)(Keep.both) { implicit b => (iss, zis) =>
        import GraphDSL.Implicits._

        val inputStreamConnector = b.add {
          Sink.foreach[(InputStream, Promise[InputStream])] { case (is, pis) =>
            pis.success(addInputBuffer(buffer)(is))
          }
        }
        val materializedStreams = b.materializedValue map { case (is, (pis, _)) => (is, pis) }
        materializedStreams ~> inputStreamConnector

        FlowShape(iss.in, zis.out)
      }
    }.mapMaterializedValue(_._2._2)
  }

  private[this] def actionToBytesFlow(outputTimeout: FiniteDuration, buffer: Option[Int], level: Option[Int])(implicit ec: ExecutionContext): Flow[ZipAction, ByteString, Future[IOResult]] = {
    Flow.fromGraph {
      val s = StreamConverters.asOutputStream(outputTimeout)
      val z = new ZipOutputSink(level, ec)
      GraphDSL.create(s, z)(Keep.both) { implicit b => (oss, zos) =>
        import GraphDSL.Implicits._

        val outputStreamConnector = b.add {
          Sink.foreach[(OutputStream, Promise[OutputStream])] { case (os, pos) =>
            pos.success(addOutputBuffer(buffer)(os))
          }
        }
        val materializedStreams = b.materializedValue .map { case (os, (pos, _)) => (os, pos) }

        materializedStreams ~> outputStreamConnector

        FlowShape(zos.in, oss.out)
      }
    }.mapMaterializedValue(_._2._2)
  }

  private[stream] sealed trait InputAction extends Serializable
  private[stream] object InputAction {
    final case object EndInput extends InputAction
  }
  private[stream] sealed trait ZipAction extends InputAction with Serializable
  private[stream] object ZipAction {
    final case class NewEntry(name: EntryMetadata) extends ZipAction
    final case class Data(data: ByteString) extends ZipAction
    final case object CloseEntry extends ZipAction
  }
}
