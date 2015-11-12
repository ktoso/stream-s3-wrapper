package io.dronekit.cloud

import java.io.ByteArrayInputStream
import java.util

import akka.event.LoggingAdapter
import akka.stream.{Attributes, Outlet, Inlet, FlowShape}
import akka.stream.stage._
import akka.util.ByteString
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._

import scala.collection.JavaConversions._
import scala.collection._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Try, Failure, Success}

/**
 * Created by Jason Martens <jason.martens@3drobotics.com> on 8/18/15.
 *
 */

/**
 * An Akka GraphStage which uploads a ByteString to S3 as a multipart upload.
 * @param s3Client The S3 client to use to connect to S3
 * @param bucket The name of the bucket to upload to
 * @param key The key to use for the upload
 * @param logger a logger for debug messages
 *
 *               This stage has a 10MB buffer for input data, as the buffer fills up, each chunk is uploaded to s3.
 *               Each uploaded chunk is kept track of in the chunkMap. When all the parts in the chunkMap are uploaded
 *               the stream finishes.
 */
class S3UploadFlow(s3Client: AmazonS3Client, bucket: String, key: String, logger: LoggingAdapter)
                  (implicit ec: ExecutionContext) extends GraphStage[FlowShape[ByteString, UploadPartResult]] {

  require(ec != null, "Execution context was null!")

  val in: Inlet[ByteString] = Inlet("S3UploadFlow.in")
  val out: Outlet[UploadPartResult] = Outlet("S3UploadFlow.out")
  override val shape: FlowShape[ByteString, UploadPartResult] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      val retries = 2

      // number of retries for each s3 part

      sealed trait UploadState

      case object UploadStarted extends UploadState

      case class UploadFailed(ex: Throwable) extends UploadState

      case object UploadCompleted extends UploadState

      case class UploadChunk(number: Long, data: ByteString, state: UploadState = UploadStarted, etag: Option[PartETag])

      case class UploadResult(number: Long, state: UploadState, etag: Option[PartETag] = None)

      val MaxQueueSize = 10
      val MinUploadChunkSize = 1024 * 1024 * 10
      // 10 MB
      val chunkMap = new mutable.LongMap[UploadChunk](MaxQueueSize)
      var buffer = ByteString.empty
      var partNumber = 1
      //      var partEtags = List[UploadResult]()
      val uploadRequest = new InitiateMultipartUploadRequest(bucket, key)
      var multipartUpload: Option[InitiateMultipartUploadResult] = None
      var uploadResults: List[UploadPartResult] = List()

      // wrap java s3 client in a try so we can abort the stage
      try {
        multipartUpload = Some(s3Client.initiateMultipartUpload(uploadRequest))
        logger.debug(s"Created multipart upload from $uploadRequest")
      } catch {
        case ex: Throwable => logger.debug(s"s3Client.initiateMultipartUpload failed with $ex")
      }

      val callback = getAsyncCallback(onAsyncInput)

      setHandler(in, new InHandler {
        /**
         * Buffer elements until we have the minimum size we would like to upload to S3
         */
        override def onPush(): Unit = {
          val elem = grab(in)
          buffer ++= elem

          if (buffer.length > MinUploadChunkSize) {
            val uploadFuture = uploadBuffer(callback)
            uploadFuture.onComplete({ trying =>
              onAsyncInput(trying)
            })
//            })(scala.concurrent.ExecutionContext.Implicits.global)
          }
          pushAndPull()
        }

        override def onUpstreamFinish(): Unit = {
          println("onUpstreamFinish")
          // Upload whatever is left in the buffer, since we will not be getting any more data
          uploadBuffer(callback).onComplete(onAsyncInput)
          pushAndPull()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          println("onPull")
          pushAndPull()
        }
      })

      /**
       * Push any available data to the out port, and pull if the in port is available.
       * When all chunks are completed, also complete the upload and the stage.
       */
      private def pushAndPull(): Unit = {
        if (uploadResults.nonEmpty && !isClosed(out) && isAvailable(out)) {
          println(s"pushing ${uploadResults.head} to out port")
          push(out, uploadResults.head)
          uploadResults = uploadResults.tail
        }
        if (!isAvailable(in) && !isClosed(in)) {
          println("pulling for more data")
          pull(in)
        }

        if (outstandingChunks == 0 && isClosed(in)) {
          try {
            println("All chunks complete and in isClosed")
            completeUpload()
            completeStage()
          } catch {
            case ex: Throwable =>
              logger.error(ex, s"Upload failed to s3://$bucket/$key while trying to complete")
              abortUpload()
              failStage(ex)
          }
        }
      }


      /**
       * Callback for when each part is done uploading
       */
      private def onAsyncInput(input: Try[UploadPartResult]): Unit = input match {
        case Failure(ex) =>
          logger.error(ex, s"Upload failed to s3://$bucket/$key")
          abortUpload()
          failStage(ex)
        case Success(result) =>
          println("onAsyncInput success")
          uploadResults = uploadResults :+ result
          setPartCompleted(result.getPartNumber, Some(result.getPartETag))
          logger.debug(s"Completed part")
          pushAndPull()

      }

      /**
       * Set a part in the chunkmap as complete
       * @param partNumber number of the chunk
       * @param etag etag of the chunk
       */
      private def setPartCompleted(partNumber: Long, etag: Option[PartETag]): Unit = {
        chunkMap(partNumber) = UploadChunk(partNumber, ByteString.empty, UploadCompleted, etag)
      }

      private def outstandingChunks: Long = {
        chunkMap.filter { case (part, chunk) => chunk.state != UploadCompleted }.toList.length
      }

      private def abortUpload() = {
        logger.debug(s"Aborting upload to $bucket/$key with id ${multipartUpload.get.getUploadId}")
        s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, multipartUpload.get.getUploadId))
      }

      private def completeUpload() = {
        val etagList = chunkMap.toList.sortBy { case (part, chunk) => part }.map { case (part, chunk) => chunk.etag.get }
        val etagArrayList: util.ArrayList[PartETag] = new util.ArrayList[PartETag](etagList.toIndexedSeq)
        val completeRequest = new CompleteMultipartUploadRequest(bucket, key, multipartUpload.get.getUploadId, etagArrayList)
        val result = s3Client.completeMultipartUpload(completeRequest)
        //        multipartCompleted = true
        logger.debug(s"Completed upload: $result")
      }

      /**
        */
      private def uploadBuffer(asyncCb: AsyncCallback[Try[UploadPartResult]]): Future[UploadPartResult] = {
        if (multipartUpload.isEmpty) {
          Future.failed(new AWSException("Could not initiate multipart upload"))
        } else {
          chunkMap(partNumber) = UploadChunk(partNumber, buffer, UploadStarted, None)
          val uploadFuture = uploadPartToAmazon(buffer, partNumber, multipartUpload.get.getUploadId, bucket, key)
          partNumber += 1
          buffer = ByteString.empty
          uploadFuture
        }
      }

      /**
       * Upload a buffer of data into s3, retries 2 times by default
       * @param buffer data to upload
       * @param partNumber the chunk for the chunkMap
       * @param multipartId id of the multipart upload
       * @param bucket s3 bucket name
       * @param key name of file
       * @return the etag of the part upload
       */
      private def uploadPartToAmazon(buffer: ByteString, partNumber: Int, multipartId: String, bucket: String,
                                     key: String): Future[UploadPartResult] = {

        //    val futureWrapper = Promise[UploadPartResult]()

        def uploadHelper(retryNumLocal: Int): Future[UploadPartResult] = {

          if (retryNumLocal >= retries)
            Future.failed(new AWSException(s"Uploading part failed for part $partNumber and multipartId: $multipartId"))
          else {
            val inputStream = new ByteArrayInputStream(buffer.toArray[Byte])
            Future {
              println("Hello")
            }
            val uploadFuture = Future {
              val partUploadRequest = new UploadPartRequest()
                .withBucketName(bucket)
                .withKey(key)
                .withUploadId(multipartId)
                .withPartNumber(partNumber)
                .withPartSize(buffer.length)
                .withInputStream(inputStream)
              println(s"partUploadRequest: $partUploadRequest s3Client: $s3Client")
              s3Client.uploadPart(partUploadRequest)
            }

            uploadFuture.recoverWith {
              case ex => println(s"Recovering from $ex"); uploadHelper(retryNumLocal + 1)
            }
            uploadFuture
          }
        }

        uploadHelper(0)
      }

    }
}
