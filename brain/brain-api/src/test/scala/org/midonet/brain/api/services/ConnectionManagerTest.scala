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

package org.midonet.cluster.util

import io.netty.channel.ChannelHandlerContext
import org.mockito.Mockito
import org.scalatest.{Matchers, FeatureSpec}

import org.midonet.brain.api.services.{ProtocolFactory, SessionManagerBase, ConnectionManager}

class ConnectionManagerTest extends FeatureSpec with Matchers {

    feature("Get the connection associated to a low level channel")
    {
        scenario("register a new connection") {
            val ctx = Mockito.mock(classOf[ChannelHandlerContext])
            val protocol = Mockito.mock(classOf[ProtocolFactory])
            val cMgr = new ConnectionManager(protocol)
            val conn = cMgr.get(ctx)

            conn should not be null
        }

        scenario("retrieve different connections") {
            val ctx1 = Mockito.mock(classOf[ChannelHandlerContext])
            val ctx2 = Mockito.mock(classOf[ChannelHandlerContext])
            val protocol = Mockito.mock(classOf[ProtocolFactory])
            val cMgr = new ConnectionManager(protocol)
            val conn1 = cMgr.get(ctx1)
            val conn2 = cMgr.get(ctx2)

            conn1 should not be null
            conn2 should not be null
            conn2 should not be conn1
        }

        scenario("retrieve the same connection several times") {
            val ctx1 = Mockito.mock(classOf[ChannelHandlerContext])
            val protocol = Mockito.mock(classOf[ProtocolFactory])
            val cMgr = new ConnectionManager(protocol)
            val conn1 = cMgr.get(ctx1)
            val conn2 = cMgr.get(ctx1)

            conn1 should not be null
            conn2 should not be null
            conn2 should be (conn1)
        }

        scenario("unregister and register a connection") {
            val ctx1 = Mockito.mock(classOf[ChannelHandlerContext])
            val protocol = Mockito.mock(classOf[ProtocolFactory])
            val cMgr = new ConnectionManager(protocol)
            val conn1 = cMgr.get(ctx1)

            conn1 should not be null

            cMgr.unregister(ctx1)
            val conn2 = cMgr.get(ctx1)

            conn1 should not be null
            conn2 should not be null
            conn2 should not be conn1
        }
    }
}


