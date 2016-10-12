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

import org.junit.runner.RunWith

import org.midonet.midolman.util.MockNetlinkChannelFactory
import org.midonet.netlink.NetlinkMessage
import org.midonet.netlink.rtnetlink.Rtnetlink
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}
import org.scalatest.concurrent._
import org.scalatest.concurrent.Eventually._

case class TcReq(msg: Int, ifi: Int)

class TestableTcRequestHandler
    extends TcRequestHandler(new MockNetlinkChannelFactory) {

    val reqs = new util.ArrayList[Int]()

    override def writeRead(buf: ByteBuffer): Unit = {
        val msgType = buf.getShort(NetlinkMessage.NLMSG_TYPE_OFFSET)
        val offset = NetlinkMessage.NLMSG_PID_OFFSET +
          NetlinkMessage.NLMSG_PID_SIZE + 4
        val ifindex = buf.getInt(offset)
        reqs.add(msgType)
        reqs.add(ifindex)
        buf.clear()
    }

    def opsMatchReqs(reqList: List[TcReq]): Boolean = {
        val expected = new util.ArrayList[Int]()
        reqList foreach { tr =>
            tr.msg match {
                case TcRequestOps.ADDFILTER =>
                    expected.add(Rtnetlink.Type.NEWQDISC)
                    expected.add(tr.ifi)
                    expected.add(Rtnetlink.Type.NEWTFILTER)
                    expected.add(tr.ifi)
                case TcRequestOps.REMQDISC =>
                    expected.add(Rtnetlink.Type.DELQDISC)
                    expected.add(tr.ifi)
            }
        }

        expected equals reqs
    }
}

@RunWith(classOf[JUnitRunner])
class TcRequestHandlerTest extends FeatureSpec
                    with BeforeAndAfterAll
                    with Matchers
                    with ScalaFutures {

    val add = TcRequestOps.ADDFILTER
    val rem = TcRequestOps.REMQDISC

    feature("Handler processes requests") {
        scenario("random requests") {

            val handler = new TestableTcRequestHandler()
            handler.startAsync().awaitRunning()

            val reqs = List(
                TcReq(add, 1), TcReq(add, 2), TcReq(rem, 1), TcReq(add, 3), TcReq(add, 4),
                TcReq(rem, 4), TcReq(add, 100), TcReq(rem, 2))

            reqs foreach { tr =>
                if (tr.msg == add) {
                    handler.addTcConfig(tr.ifi, 300, 200)
                } else {
                    handler.delTcConfig(tr.ifi)
                }
            }

            eventually {
                handler.opsMatchReqs(reqs) shouldBe true
            }
        }
    }
}

