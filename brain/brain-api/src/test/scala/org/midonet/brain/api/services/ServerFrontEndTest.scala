/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.api.services

import com.google.protobuf.Message

import org.mockito.Matchers._
import org.mockito.Mockito
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.rpc.Commands

import io.netty.channel.ChannelHandlerContext

class ServerFrontEndTest extends FeatureSpec with Matchers {

    feature("plain socket server")
    {
        val port = 4242
        val protocol = new DefaultProtocolFactory(new SessionManager)
        val connMgr = new ConnectionManager(protocol)
        val expected = Commands.Request.getDefaultInstance

        scenario("service life cycle") {
            val reqHandler = new RequestHandler(connMgr)
            val handler = new ApiServerHandler(reqHandler.subject)
            val srv = new ServerFrontEnd(new PlainAdapter(handler, expected),
                                         port)

            srv.startAsync().awaitRunning()
            srv.isRunning should be (true)
            srv.stopAsync().awaitTerminated()
            srv.isRunning should be (false)
        }
    }

    feature("websocket-based server")
    {
        val port = 4242
        val path = "/websocket"
        val protocol = new DefaultProtocolFactory(new SessionManager)
        val connMgr = new ConnectionManager(protocol)
        val expected = Commands.Request.getDefaultInstance

        scenario("service life cycle") {
            val reqHandler = new RequestHandler(connMgr)
            val handler = new ApiServerHandler(reqHandler.subject)
            val srv = new ServerFrontEnd(
                new WebSocketAdapter(handler, expected, path), port)

            srv.startAsync().awaitRunning()
            srv.isRunning should be (true)
            srv.stopAsync().awaitTerminated()
            srv.isRunning should be (false)
       }
    }
}


