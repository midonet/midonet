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

package org.midonet.cluster.services.endpoint

import java.io.File
import java.net.{BindException, ServerSocket}

import scala.collection.JavaConversions._

import com.google.common.io.Files
import com.google.inject.AbstractModule

import org.apache.commons.io.FileUtils
import org.apache.http.conn.HttpHostConnectException
import org.junit.runner.RunWith
import org.mockito.{ArgumentCaptor, Mockito, Matchers => MockMatchers}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.EndpointConfig.SSLSource
import org.midonet.cluster.services.endpoint.EndpointServiceTest.{DisabledUser, EarlyUser, LateUser}
import org.midonet.cluster.services.endpoint.EndpointTestUtils.TestEndpointUser
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.{MidonetEventually, PortProvider}

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.{HttpMethod, HttpRequest}

@RunWith(classOf[JUnitRunner])
class EndpointServiceTest extends FeatureSpec with Matchers with BeforeAndAfter
                                  with CuratorTestFramework with MidonetEventually {

    private implicit val serviceClassBeingTested = classOf[EndpointService]

    private var tmpDir: File = _

    before {
        tmpDir = Files.createTempDir()
        FileUtils.forceDeleteOnExit(tmpDir)
    }

    after {
        FileUtils.deleteQuietly(tmpDir)
    }

    private def testBasicLifecycle(ssl: EndpointTestUtils.SSLConfig) = {
        val confStr = EndpointTestUtils.genConfString(ssl = ssl)
        implicit val testContext = EndpointTestUtils.initTest(curator, confStr)
        val srv: EndpointService = ServiceTestUtils.getService

        srv.isEnabled shouldBe true
        ServiceTestUtils.startAndWaitRunning(srv)
        srv.isRunning shouldBe true

        ServiceTestUtils.stopAndWaitStopped(srv)
        srv.isRunning shouldBe false
    }

    private def testChannelInitBasedOnPath(ssl: EndpointTestUtils.SSLConfig) = {
        val protocol = if (ssl.enabled) "https" else "http"
        val port = PortProvider.getPort
        val confStr = EndpointTestUtils.genConfString(port = port, ssl = ssl)

        implicit val testContext = EndpointTestUtils.initTest(curator, confStr)

        // Setup channel initializers
        val testPath1 = "/test1"
        val testPath1Handler =
            Mockito.spy(new EndpointTestUtils.TestHTTPEndpointHandler)
        val testPath1User = EndpointTestUtils.BasicTestEndpointUser(
            path = testPath1,
            handler = Some(testPath1Handler))
        val testPath2 = "/test2"
        val testPath2Sub = testPath2 + "/sub"
        val testPath2Handler =
            Mockito.spy(new EndpointTestUtils.TestHTTPEndpointHandler)
        val testPath2User = EndpointTestUtils.BasicTestEndpointUser(
            path = testPath2,
            handler = Some(testPath2Handler))

        val srv: EndpointService = ServiceTestUtils.getService

        srv.userRegistrar.register(testPath1User)
        srv.userRegistrar.register(testPath2User)

        ServiceTestUtils.startAndWaitRunning(srv)

        val paths = List(testPath1, testPath2, testPath2Sub, testPath1)
        val urls = paths
            .map(p => EndpointTestUtils.constructEndpointUrl(protocol, port, p))

        // Send GET requests to paths handled by the previous channels
        val results = urls.map(u => EndpointTestUtils.sendHttpGet(u))

        // # Check responses

        // Client should receive responses to all requests. If this doesn't
        // happen, a TimeoutException will be thrown
        val responses = results.map(ServiceTestUtils.await(_))

        // Responses should all be with code 200
        responses.foreach(_.getStatusLine.getStatusCode shouldBe 200)

        val channelHandlerContextClass = classOf[ChannelHandlerContext]

        // # Check handler calls and inputs

        // For path1
        val path1Captor = ArgumentCaptor.forClass(classOf[HttpRequest])
        Mockito.verify(testPath1Handler, Mockito.times(2))
            .channelRead0(MockMatchers.any(channelHandlerContextClass),
                          path1Captor.capture())
        path1Captor.getAllValues.foreach(_.method shouldBe HttpMethod.GET)
        path1Captor.getAllValues.foreach(_.uri shouldBe testPath1)

        // For path2
        val path2Captor = ArgumentCaptor.forClass(classOf[HttpRequest])
        Mockito.verify(testPath2Handler, Mockito.times(2))
            .channelRead0(MockMatchers.any(channelHandlerContextClass),
                          path2Captor.capture())
        path2Captor.getAllValues.foreach(_.method shouldBe HttpMethod.GET)
        path2Captor.getAllValues.map(_.uri).toSet shouldBe
            Set(testPath2, testPath2Sub)

        // Shutdown
        ServiceTestUtils.stopAndWaitStopped(srv)
    }

    feature("Endpoint service") {
        scenario("basic lifecycle - with self-signed ssl") {
            testBasicLifecycle(ssl = EndpointTestUtils.SSLConfig())
        }

        scenario("basic lifecycle - without ssl") {
            testBasicLifecycle(
                ssl = EndpointTestUtils.SSLConfig(enabled = false))
        }

        scenario("starting on a busy port") {
            // Bind a socket to a port
            val sock = new ServerSocket(0)
            val port = sock.getLocalPort
            val confStr = EndpointTestUtils.genConfString(port = port)

            implicit val testContext = EndpointTestUtils.initTest(curator,
                                                                  confStr)

            val srv: EndpointService = ServiceTestUtils.getService

            // Starting a server in the same port should fail
            val exc = the [IllegalStateException] thrownBy
                ServiceTestUtils.startAndWaitRunning(srv)

            exc.getCause.getClass shouldBe classOf[BindException]
            srv.isRunning shouldBe false

            sock.close()
        }

        scenario("interface binding - default, bind to all") {
            val port = PortProvider.getPort
            val hostIP = EndpointTestUtils.getAnyNonLoopbackHostIP()

            assume(hostIP.nonEmpty)

            hostIP.foreach { ip =>
                val confStr = EndpointTestUtils.genConfString(port = port)

                implicit val testContext = EndpointTestUtils.initTest(curator,
                                                                      confStr)

                val srv: EndpointService = ServiceTestUtils.getService

                ServiceTestUtils.startAndWaitRunning(srv)

                val result1 = EndpointTestUtils.sendHttpGet(s"https://$ip:$port")
                noException should be thrownBy ServiceTestUtils.await(result1)

                val result2 = EndpointTestUtils.sendHttpGet(s"https://127.0.0.1:$port")
                noException should be thrownBy ServiceTestUtils.await(result2)

                ServiceTestUtils.stopAndWaitStopped(srv)
            }
        }

        scenario("interface binding - bind to single interface") {
            val port = PortProvider.getPort
            val hostIP = EndpointTestUtils.getAnyNonLoopbackHostIP()

            assume(hostIP.nonEmpty)

            hostIP.foreach { ip =>
                val confStr = EndpointTestUtils.genConfString(port = port,
                                                              interface = ip)

                implicit val testContext = EndpointTestUtils.initTest(curator,
                                                                      confStr)

                val srv: EndpointService = ServiceTestUtils.getService

                ServiceTestUtils.startAndWaitRunning(srv)

                val result1 = EndpointTestUtils.sendHttpGet(s"https://$ip:$port")
                noException should be thrownBy ServiceTestUtils.await(result1)

                val result2 = EndpointTestUtils.sendHttpGet(s"https://127.0.0.1:$port")
                an [HttpHostConnectException] should be thrownBy
                    ServiceTestUtils.await(result2)

                ServiceTestUtils.stopAndWaitStopped(srv)
            }
        }

        scenario("channel initialization based on path without ssl") {
            testChannelInitBasedOnPath(
                ssl = EndpointTestUtils.SSLConfig(enabled = false))
        }

        scenario("channel initialization based on path with ssl") {
            testChannelInitBasedOnPath(
                ssl = EndpointTestUtils.SSLConfig(enabled = true))
        }

        scenario("unknown paths should return 404") {
            val port = PortProvider.getPort
            val confStr = EndpointTestUtils.genConfString(port = port)

            implicit val testContext = EndpointTestUtils.initTest(curator,
                                                                  confStr)

            val srv: EndpointService = ServiceTestUtils.getService

            ServiceTestUtils.startAndWaitRunning(srv)

            // Send a GET request to a path that has no channel init associated
            val url = EndpointTestUtils.constructEndpointUrl(port=port,
                                                             path="/unknown")
            val result = EndpointTestUtils.sendHttpGet(url)

            // # Check responses

            // Client should receive a response to the request. If this doesn't
            // happen, the get will throw a TimeoutException
            val response = ServiceTestUtils.await(result)

            // Response should be a 404
            response.getStatusLine.getStatusCode shouldBe 404

            // Shutdown
            ServiceTestUtils.stopAndWaitStopped(srv)
        }
    }

    feature("Non-self-signed SSL sources") {
        def testJKSSSLConfig = {
            val jksFile =
                EndpointTestUtils.extractResourceToTemporaryFile("/test.jks",
                                                                 tmpDir)

            EndpointTestUtils.SSLConfig(
                enabled = true,
                source = SSLSource.Keystore,
                keystorePath = jksFile.getAbsolutePath,
                keystorePassword = "testpassword"
            )
        }

        scenario("basic lifecycle with jks keystore ssl") {
            testBasicLifecycle(testJKSSSLConfig)
        }

        scenario("channel initialization based on path with jks keystore ssl") {
            testChannelInitBasedOnPath(testJKSSSLConfig)
        }

        def testP12SSLConfig = {
            val p12File =
                EndpointTestUtils.extractResourceToTemporaryFile("/test.p12",
                                                                 tmpDir)

            EndpointTestUtils.SSLConfig(
                enabled = true,
                source = SSLSource.Keystore,
                keystorePath = p12File.getAbsolutePath,
                keystorePassword = "testpassword"
            )
        }

        scenario("basic lifecycle with p12 keystore ssl") {
            testBasicLifecycle(testP12SSLConfig)
        }

        scenario("channel initialization based on path with p12 keystore ssl") {
            testChannelInitBasedOnPath(testP12SSLConfig)
        }

        def testCertificateSSLConfig = {
            val certificateFile =
                EndpointTestUtils.extractResourceToTemporaryFile("/test.crt",
                                                                 tmpDir)
            val privateKeyFile =
                EndpointTestUtils.extractResourceToTemporaryFile("/test.key",
                                                                 tmpDir)

            EndpointTestUtils.SSLConfig(
                enabled = true,
                source = SSLSource.Certificate,
                certificatePath = certificateFile.getAbsolutePath,
                privateKeyPath = privateKeyFile.getAbsolutePath,
                privateKeyPassword = "testpassword"
            )
        }

        scenario("basic lifecycle with certificate ssl") {
            testBasicLifecycle(testCertificateSSLConfig)
        }

        scenario("channel initialization based on path with certificate ssl") {
            testChannelInitBasedOnPath(testCertificateSSLConfig)
        }
    }

    feature("Endpoint service user detection and registration") {

        scenario("register endpoint service users") {
            val confStr = EndpointTestUtils.genConfString()

            val earlyUser = new EarlyUser
            val lateUser = new LateUser
            val disabledUser = new DisabledUser

            // Create some fake endpoint service users
            // Add them to the injector
            val userModule = new AbstractModule {
                override def configure() = {
                    bind(classOf[EarlyUser]).toInstance(earlyUser)
                    bind(classOf[LateUser]).toInstance(lateUser)
                    bind(classOf[DisabledUser]).toInstance(disabledUser)
                }
            }

            implicit val testContext = EndpointTestUtils.initTest(curator,
                                                                  confStr,
                                                                  userModule)

            val srv: EndpointService = ServiceTestUtils.getService

            // Start early user before the server
            ServiceTestUtils.startAndWaitRunning(earlyUser)
            srv.userRegistrar.register(earlyUser)

            // Start the server
            ServiceTestUtils.startAndWaitRunning(srv)

            // After starting there should have been no registrations for late
            // or disabled users
            srv.userRegistrar.findUserForPath(
                lateUser.endpointPath) shouldBe None
            srv.userRegistrar.findUserForPath(
                disabledUser.endpointPath) shouldBe None

            // But the early user should have been registered already
            eventually {
                srv.userRegistrar.findUserForPath(earlyUser.endpointPath) shouldBe
                Some(earlyUser.endpointPath, earlyUser)
            }
            // Start late user now
            ServiceTestUtils.startAndWaitRunning(lateUser)
            srv.userRegistrar.register(lateUser)

            // Late user should have been registered
            eventually {
                srv.userRegistrar.findUserForPath(
                    lateUser.endpointPath) shouldBe Some(lateUser.endpointPath,
                                                         lateUser)
            }
            // Wait for threads to realize there is no more work
            Thread.sleep(5000)
            // They should have died on their own
            srv.userRegistrationExecutor.getPoolSize shouldBe 0

            ServiceTestUtils.stopAndWaitStopped(srv)
        }
    }
}

object EndpointServiceTest {
    class CustomTestEndpointUser(path: String, enabled: Boolean = true)
        extends TestEndpointUser {

        override def testHandler = None
        override def protocol(useSSL: Boolean) = "http"
        override def endpointPath = path
        override def isEnabled = enabled
    }

    class EarlyUser extends CustomTestEndpointUser("/early")
    class LateUser extends CustomTestEndpointUser("/late")
    class DisabledUser extends CustomTestEndpointUser("/disabled", false)
}
