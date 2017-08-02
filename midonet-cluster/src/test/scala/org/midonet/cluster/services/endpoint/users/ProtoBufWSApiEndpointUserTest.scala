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

import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Mockito, Matchers => MockMatchers}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Inside, Matchers}

import org.midonet.cluster.services.endpoint.{EndpointService, EndpointTestUtils, ServiceTestUtils}
import org.midonet.cluster.services.endpoint.EndpointTestUtils.SSLConfig
import org.midonet.cluster.services.endpoint.comm._
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.api.common.Api
import org.midonet.util.PortProvider

import io.netty.channel.ChannelFutureListener

@RunWith(classOf[JUnitRunner])
class ProtoBufWSApiEndpointUserTest extends FeatureSpec with Matchers with Inside
                                            with BeforeAndAfter
                                            with CuratorTestFramework {

    private implicit val serviceClassBeingTested = classOf[EndpointService]

    private def testAPIEndpointChannel(ssl: SSLConfig) = {
        val port = PortProvider.getPort
        val confStr = EndpointTestUtils.genConfString(port = port, ssl = ssl)

        implicit val testContext = EndpointTestUtils.initTest(curator, confStr)

        val srv = ServiceTestUtils.getService

        // Mock connection handler to count invocations, capture parameters,
        // make it echo back received message and then close the channel
        val testCnxHandler = Mockito.mock(
            classOf[ConnectionHandler[Api.Request, Api.Response]])
        Mockito.when(testCnxHandler.onNext(
            MockMatchers.any(classOf[IncomingEvent])))
            .thenAnswer(new Answer[Void] {
                override def answer(invocation: InvocationOnMock): Void = {
                    invocation.getArguments()(0) match {
                        case IncomingMsg(ctx, msg) =>
                            // Echo
                            ctx.writeAndFlush(msg)
                                .addListener(ChannelFutureListener.CLOSE)
                        case _ =>
                        // Ignore
                    }
                    null
                }
            })

        // Create an API endpoint using our mocked connection handler
        val testPath = "/api"
        val testPathUser = EndpointTestUtils.ApiTestEndpointServiceUser(
            Api.Request.getDefaultInstance, testCnxHandler, path = testPath)

        srv.userRegistrar.register(testPathUser)

        ServiceTestUtils.startAndWaitRunning(srv)

        // Send a Handshake protobuf message and wait for channel close
        val handshakeBuilder = Api.Handshake.newBuilder
            .setAuth("test")
            .setReqId(1)
        val message = Api.Request.newBuilder
            .setHandshake(handshakeBuilder)
            .build()
        val result = EndpointTestUtils.sendProtoBufWSMessage(
            EndpointTestUtils.constructEndpointUrl(
                protocol=if (ssl.enabled) "wss" else "ws", port=port,
                path=testPath),
            message)

        val responseOption = ServiceTestUtils.await(result)

        // Result should be the same message echoed back
        responseOption shouldBe Some(message)

        // Wait to give time to the server to process the connection close.
        Thread.sleep(500)

        // Shutdown to ensure all pending things are processed
        ServiceTestUtils.stopAndWaitStopped(srv)

        val captor = ArgumentCaptor.forClass(classOf[IncomingEvent])

        // onNext should have been called 3 times
        Mockito.verify(testCnxHandler, Mockito.times(3))
            .onNext(captor.capture())

        // First should receive an incoming connect
        captor.getAllValues.get(0) should matchPattern {
            case IncomingConnect(_) =>
        }

        // Then the actual message
        inside(captor.getAllValues.get(1)) {
            case IncomingMsg(_, msg) => msg shouldBe message
        }

        // Then the disconnect
        captor.getAllValues.get(2) should matchPattern {
            case IncomingDisconnect(_) =>
        }
    }


    feature("API endpoint channel") {
        scenario("api call handled correctly without ssl") {
            testAPIEndpointChannel(ssl = SSLConfig(enabled = false))
        }

        scenario("api call handled correctly with ssl") {
            testAPIEndpointChannel(ssl = SSLConfig(enabled = true))
        }
    }
}
