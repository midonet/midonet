/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.host.services

import java.nio.ByteBuffer
import java.util
import java.util.concurrent.LinkedBlockingQueue

import org.junit.runner.RunWith
import org.midonet.midolman.util.MockNetlinkChannelFactory
import org.midonet.netlink.NetlinkMessage
import org.midonet.netlink.rtnetlink.Rtnetlink
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}
import org.scalatest.concurrent._
import org.scalatest.concurrent.Eventually._

@RunWith(classOf[JUnitRunner])
class TcRequestHandlerTest extends FeatureSpec
                    with BeforeAndAfterAll
                    with Matchers
                    with ScalaFutures {

    class TestableTcRequestHandler(q: LinkedBlockingQueue[TcRequest])
        extends TcRequestHandler(new MockNetlinkChannelFactory(), q) {

        val reqs = new util.ArrayList[Int]()

        def pop = reqs.remove(0)

        override def writeRead(buf: ByteBuffer): Unit = {
            val msgType = buf.getShort(NetlinkMessage.NLMSG_TYPE_OFFSET)
            val offset = NetlinkMessage.NLMSG_PID_OFFSET +
                         NetlinkMessage.NLMSG_PID_SIZE + 4
            val ifindex = buf.getInt(offset)
            reqs.add(msgType)
            reqs.add(ifindex)
            buf.clear()
        }
    }

    val add = TcRequestOps.ADDFILTER
    val rem = TcRequestOps.REMQDISC

    feature("Handler processes requests") {
        scenario("random requests") {

            val q = new LinkedBlockingQueue[TcRequest]()
            val handler = new TestableTcRequestHandler(q)

            handler.start()

            val reqTypes = List(add, add, rem, add, add, rem, add, rem)
            val indexes = List(1, 2, 1, 3, 4, 4, 100, 2)

            for ((r, i) <- reqTypes zip indexes) {
                q.add(new TcRequest(r, i, 300, 200))
            }

            eventually {
                val adds = reqTypes filter(add equals)
                val rems = reqTypes filter(rem equals)
                handler.reqs.size() shouldBe ((adds.size * 4) + (rems.size * 2))
            }

            eventually {
                for ((r, i) <- reqTypes zip indexes) {
                    if (r == add) {
                        handler.pop shouldBe Rtnetlink.Type.NEWQDISC
                        handler.pop shouldBe i
                        handler.pop shouldBe Rtnetlink.Type.NEWTFILTER
                        handler.pop shouldBe i
                    } else {
                        handler.pop shouldBe Rtnetlink.Type.DELQDISC
                        handler.pop shouldBe i
                    }
                }
            }
        }
    }
}

