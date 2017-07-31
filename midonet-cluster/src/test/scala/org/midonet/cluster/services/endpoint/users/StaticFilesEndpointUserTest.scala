/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.endpoint.users

import java.io.File

import com.google.common.io.Files

import org.apache.commons.io.{FileUtils, IOUtils}
import org.junit.runner.RunWith
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Milliseconds, Span}
import org.scalatest.{BeforeAndAfter, FeatureSpec, Inside, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.services.endpoint.{EndpointService, EndpointTestUtils, ServiceTestUtils}
import org.midonet.cluster.services.endpoint.EndpointTestUtils.{SSLConfig, SingleChannelHttpClient}
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.{MidonetEventually, PortProvider}
import org.midonet.util.io.RandomFile

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.handler.codec.http.HttpHeaderNames.CONNECTION
import io.netty.handler.codec.http.HttpHeaderValues.{CLOSE, KEEP_ALIVE}
import io.netty.handler.codec.http.HttpResponseStatus

@RunWith(classOf[JUnitRunner])
class StaticFilesEndpointUserTest extends FeatureSpec with Matchers with Inside
                                          with BeforeAndAfter
                                          with CuratorTestFramework
                                          with MidonetEventually {

    import StaticFilesEndpointUserTest._

    private implicit val serviceClassBeingTested = classOf[EndpointService]

    private var tmpDir: File = _

    before {
        tmpDir = Files.createTempDir()
        FileUtils.forceDeleteOnExit(tmpDir)
    }

    after {
        FileUtils.deleteQuietly(tmpDir)
    }

    private def createTempFile(path: String, length: Int = 20) = {
        val file = RandomFile(tmpDir.getAbsolutePath, path, length)

        file.deleteOnExit()
        file
    }

    private def testStaticFilesEndpointChannel(ssl: SSLConfig) = {
        val port = PortProvider.getPort
        val confStr = EndpointTestUtils.genConfString(port = port, ssl = ssl)

        implicit val testContext = EndpointTestUtils.initTest(curator, confStr)

        val srv: EndpointService = ServiceTestUtils.getService

        // Create an static file serving endpoint
        val testUser = EndpointTestUtils.StaticFilesTestEndpointUser(
            tmpDir.getAbsolutePath,
            path = TestPath)

        srv.userRegistrar.register(testUser)

        // Create a 4MB file to get (make sure aggregation works)
        val filePath = "/someDir/someFile"
        val file = createTempFile(filePath, length = 4 * 1024 * 1024)

        ServiceTestUtils.startAndWaitRunning(srv)

        val fileUrl = EndpointTestUtils.constructEndpointUrl(
            if (ssl.enabled) "https" else "http",
            port,
            path = TestPath + filePath
        )

        val result = EndpointTestUtils.sendHttpGet(fileUrl)
        val response = ServiceTestUtils.await(result)

        response.getStatusLine.getStatusCode shouldBe
            HttpResponseStatus.OK.code

        val receivedFileBytes = IOUtils.toByteArray(
            response.getEntity.getContent)
        val realFileBytes = FileUtils.readFileToByteArray(file)

        receivedFileBytes shouldBe realFileBytes

        ServiceTestUtils.stopAndWaitStopped(srv)
    }

    private def createService(port: Int,
                              onClose: Option[ChannelHandler] = None,
                              idleTimeout : Option[Int] = None
                             ): EndpointService = {

        val confStr = EndpointTestUtils.genConfString(
            port = port, ssl = SSLConfig(enabled = false))

        implicit val testContext = EndpointTestUtils.initTest(curator, confStr)

        val srv: EndpointService = ServiceTestUtils.getService

        // Create an static file serving endpoint
        val testUser = EndpointTestUtils.StaticFilesTestEndpointUser(
            tmpDir.getAbsolutePath,
            path = TestPath,
            onCloseListener = onClose,
            idleTimeoutMs = idleTimeout)

        srv.userRegistrar.register(testUser)

        ServiceTestUtils.startAndWaitRunning(srv)

        srv
    }

    feature("Static files endpoint channel") {
        scenario("api call handled correctly without ssl") {
            testStaticFilesEndpointChannel(ssl = SSLConfig(enabled = false))
        }

        scenario("api call handled correctly with ssl") {
            testStaticFilesEndpointChannel(ssl = SSLConfig(enabled = true))
        }
    }

    feature("Keep-alive connection") {
        scenario("valid get without keep alive") {
            val port = PortProvider.getPort
            val closeHandler = new CloseHandler
            val srv = createService(port, Some(closeHandler))

            val fileName = "/jabba.jpg"
            createTempFile(fileName)

            val client = new SingleChannelHttpClient(HostName, port)
            val response = client.get(TestPath + fileName,
                Map((CONNECTION.toString(), CLOSE.toString())))

            response.getStatusLine.getStatusCode shouldBe
                HttpResponseStatus.OK.code

            eventually(Timeout(Span(1000, Milliseconds))) {
                closeHandler.invokedClose shouldBe 1
            }

            client.close
            ServiceTestUtils.stopAndWaitStopped(srv)
        }

        scenario("valid get with keep alive") {
            val port = PortProvider.getPort
            val closeHandler = new CloseHandler
            val srv = createService(port, Some(closeHandler))

            val fileName = "/jabba.jpg"
            createTempFile(fileName)

            val client = new SingleChannelHttpClient(HostName, port)
            val response = client.get(TestPath + fileName,
                Map((CONNECTION.toString(), KEEP_ALIVE.toString())))

            response.getStatusLine.getStatusCode shouldBe
                HttpResponseStatus.OK.code

            closeHandler.invokedClose shouldBe 0

            client.close
            ServiceTestUtils.stopAndWaitStopped(srv)
        }

        scenario("invalid get with keep alive") {
            val port = PortProvider.getPort
            val closeHandler = new CloseHandler
            val srv = createService(port, Some(closeHandler))

            createTempFile("/jabba.jpg")

            val client = new SingleChannelHttpClient(HostName, port)
            val response = client.get(TestPath + "/tralara.jpg",
                Map((CONNECTION.toString(), KEEP_ALIVE.toString())))

            response.getStatusLine.getStatusCode shouldBe
                HttpResponseStatus.NOT_FOUND.code

            closeHandler.invokedClose shouldBe 0

            client.close
            ServiceTestUtils.stopAndWaitStopped(srv)
        }

        scenario("idle timeout with keep alive") {
            val port = PortProvider.getPort
            val closeHandler = new CloseHandler
            val timeout = 500
            val srv = createService(port, Some(closeHandler),
                                    idleTimeout = Some(timeout))

            val file = createTempFile( "/n3.jpg", length = 30)

            val client = new SingleChannelHttpClient(HostName, port)
            (1 to 6).foreach(n => {
                Log.debug(s"Invoking service for ${n} time")
                val response = client.get( TestPath + s"/n${n}.jpg",
                    Map((CONNECTION.toString(), KEEP_ALIVE.toString())))
                if (n == 3) {
                    response.getStatusLine.getStatusCode shouldBe
                    HttpResponseStatus.OK.code
                    val receivedFileBytes = IOUtils.toByteArray(
                        response.getEntity.getContent)
                    val realFileBytes = FileUtils.readFileToByteArray(file)

                    receivedFileBytes shouldBe realFileBytes
                } else {
                    response.getStatusLine.getStatusCode shouldBe
                       HttpResponseStatus.NOT_FOUND.code
                }

                closeHandler.invokedClose shouldBe 0
                Thread.sleep(100)
                closeHandler.invokedClose shouldBe 0
            })

            eventually(Timeout(Span(1000, Milliseconds))) {
                closeHandler.invokedClose shouldBe 1
            }
            client.close
            ServiceTestUtils.stopAndWaitStopped(srv)
        }
    }
}

object StaticFilesEndpointUserTest {
    private final val TestPath = "/static"
    private final val HostName = "localhost"
    private final val Log = LoggerFactory.getLogger(classOf[StaticFilesEndpointUserTest])

    @Sharable
    private class CloseHandler extends ChannelOutboundHandlerAdapter {
        var invokedClose = 0
        override def close(ctx: ChannelHandlerContext,
                           promise: ChannelPromise): Unit = {
            Log.debug("Test CloseHandler registered close event")
            invokedClose += 1
            super.close(ctx, promise)
        }
    }
}