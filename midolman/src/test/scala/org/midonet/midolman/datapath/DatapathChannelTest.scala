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

import org.jctools.queues.SpscArrayQueue

import org.junit.runner.RunWith
import org.midonet.sdn.flows.ManagedFlow
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._

import org.midonet.midolman.FlowController.FlowRemoveCommand
import org.midonet.midolman.flows.FlowEjector
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.netlink.{MockNetlinkChannelFactory, NetlinkMessage}
import org.midonet.odp._
import org.midonet.odp.family.{DatapathFamily, FlowFamily, PacketFamily, PortFamily}
import org.midonet.odp.flows.{FlowKey, FlowKeys, FlowAction, FlowActions}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.util.collection.ArrayObjectPool

@RunWith(classOf[JUnitRunner])
class DatapathChannelTest extends MidolmanSpec {

    val factory = new MockNetlinkChannelFactory
    val nlChannel = factory.channel
    nlChannel.setPid(10)

    val ejector = new FlowEjector(10)
    val ovsFamilies = new OvsNetlinkFamilies(new DatapathFamily(1), new PortFamily(2),
                                             new FlowFamily(3), new PacketFamily(4), 5, 6)
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

    override def beforeTest() {
        dpChannel.start(new Datapath(datapathId, "midonet"))
    }

    override def afterTest() {
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
                                     flowMatchParser, packet.getMatch)
            attributeListShouldMatch(bb, OpenVSwitch.Packet.Attr.Actions,
                                     FlowActions.reader.deserializeFrom, actions)
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
                                     flowMatchParser, packet.getMatch)
            attributeListShouldMatch(bb, OpenVSwitch.Flow.Attr.Actions,
                                     FlowActions.reader.deserializeFrom, actions)
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
            val flowDelete = new FlowRemoveCommand(new ArrayObjectPool(0, _ => null),
                                                   new SpscArrayQueue[FlowRemoveCommand](16))
            val managedFlow = new ManagedFlow(null)
            managedFlow.flowMatch.reset(packet.getMatch)
            flowDelete.reset(managedFlow, 0)
            ejector.eject(flowDelete)

            Thread.sleep(500)

            nlChannel.packetsWritten.get() should be (1)

            dpChannel.createFlow(new Flow(packet.getMatch, actions))

            eventually {
                nlChannel.packetsWritten.get() should be (3)
            }

            flowDelete.reset(managedFlow, 0)
            ejector.eject(flowDelete)

            eventually {
                nlChannel.packetsWritten.get() should be (4)
            }
        }
    }

    private def flowMatchParser(buf: ByteBuffer): FlowMatch = {
        val keys = new ArrayList[FlowKey](16)
        FlowKeys.buildFrom(buf, keys)
        new FlowMatch(keys)
    }

    private def attributeListShouldMatch[T](bb: ByteBuffer, key: Short,
                                            reader: ByteBuffer => T, expected: T) = {
        val attrLen = bb.getShort
        val id = NetlinkMessage.unnest(bb.getShort)
        id should be (key)
        val next = bb.position() + attrLen - 4
        val limit = bb.limit()
        bb.limit(next)
        reader(bb) should be (expected)
        bb.position(next)
        bb.limit(limit)
    }
}
