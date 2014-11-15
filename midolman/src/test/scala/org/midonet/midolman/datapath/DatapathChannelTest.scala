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

package org.midonet.midolman.datapath

import java.nio.ByteBuffer
import java.util.ArrayList

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._

import org.midonet.midolman.flows.FlowEjector
import org.midonet.netlink.{Reader, NetlinkMessage, MockNetlinkChannelFactory}
import org.midonet.odp.family.{PortFamily, PacketFamily, FlowFamily, DatapathFamily}
import org.midonet.odp.flows.{FlowActions, FlowAction}
import org.midonet.odp._
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.util.concurrent.MockClock

@RunWith(classOf[JUnitRunner])
class DatapathChannelTest extends FeatureSpec
                          with BeforeAndAfter
                          with ShouldMatchers
                          with OneInstancePerTest {

    val factory = new MockNetlinkChannelFactory
    val nlChannel = factory.channel
    nlChannel.setPid(10)

    val ejector = new FlowEjector(10)
    val ovsFamilies = new OvsNetlinkFamilies(new DatapathFamily(1), new PortFamily(2),
                                             new FlowFamily(3), new PacketFamily(4), 5, 6)
    val clock = new MockClock
    val dpChannel = new DisruptorDatapathChannel(capacity = 16, threads = 1,
                                                 ejector, factory, ovsFamilies,
                                                 clock)

    val ethernet = { eth src MAC.random() dst MAC.random() } <<
                   { ip4 src IPv4Addr.random dst IPv4Addr.random } << payload(Array[Byte](0))

    val packet = new Packet(ethernet, FlowMatches.fromEthernetPacket(ethernet))
    val actions = new ArrayList[FlowAction]()

    {
        actions.add(FlowActions.output(1))
    }

    val datapathId = 11

    before {
        dpChannel.start(new Datapath(datapathId, "midonet"))
    }

    after {
        dpChannel.stop()
    }

    feature ("DatapathChannel handles the simulation's output") {
        scenario ("Can execute packets") {
            dpChannel.executePacket(packet, actions)

            eventually {
                nlChannel.packetsWritten.get() should be (1)
            }

            val bb = nlChannel.written.poll()
            bb.getInt(NetlinkMessage.NLMSG_PID_OFFSET) should be (10)
            bb.position(NetlinkMessage.GENL_HEADER_SIZE)
            bb.getInt() should be (datapathId)

            attributeListShouldMatch(bb, OpenVSwitch.Packet.Attr.Key,
                                     FlowMatch.reader, packet.getMatch)
            attributeListShouldMatch(bb, OpenVSwitch.Packet.Attr.Actions,
                                     FlowActions.reader, actions)
            val attrLen = bb.getShort
            NetlinkMessage.unnest(bb.getShort) should be (OpenVSwitch.Packet.Attr.Packet)
            val next = bb.position() + attrLen - 4
            bb.limit(next)
            new Ethernet().deserialize(bb) should be (packet.getEthernet)
        }

        scenario ("Can create flows") {
            dpChannel.createFlow(new Flow(packet.getMatch, actions))

            eventually {
                nlChannel.packetsWritten.get() should be (1)
            }

            val bb = nlChannel.written.poll()
            bb.getInt(NetlinkMessage.NLMSG_PID_OFFSET) should be (10)
            bb.position(NetlinkMessage.GENL_HEADER_SIZE)
            bb.getInt() should be (datapathId)

            attributeListShouldMatch(bb, OpenVSwitch.Flow.Attr.Key,
                                     FlowMatch.reader, packet.getMatch)
            attributeListShouldMatch(bb, OpenVSwitch.Flow.Attr.Actions,
                                     FlowActions.reader, actions)
        }

        scenario ("Channel is bounded and thread spins when ring buffer is full") {
            var i = 0
            while (i < 10000) {
                dpChannel.executePacket(packet, actions)
                i += 1
                i - nlChannel.packetsWritten.get() should be <= dpChannel.capacity
            }
        }

        scenario ("Can delete flows only after they have been created") {
            dpChannel.createFlow(new Flow(packet.getMatch, actions))

            eventually {
                nlChannel.packetsWritten.get() should be (1)
            }

            packet.getMatch.getSequence should be (0)

            packet.getMatch.setSequence(1)
            ejector.eject(packet.getMatch)

            Thread.sleep(500)

            nlChannel.packetsWritten.get() should be (1)

            dpChannel.createFlow(new Flow(packet.getMatch, actions))

            eventually {
                nlChannel.packetsWritten.get() should be (3)
            }

            ejector.eject(packet.getMatch)

            eventually {
                nlChannel.packetsWritten.get() should be (4)
            }
        }
    }

    private def attributeListShouldMatch[T](bb: ByteBuffer, key: Short,
                                            reader: Reader[T], expected: T) = {
        val attrLen = bb.getShort
        val id = NetlinkMessage.unnest(bb.getShort)
        id should be (key)
        val next = bb.position() + attrLen - 4
        val limit = bb.limit()
        bb.limit(next)
        reader.deserializeFrom(bb) should be (expected)
        bb.position(next)
        bb.limit(limit)
    }
}
