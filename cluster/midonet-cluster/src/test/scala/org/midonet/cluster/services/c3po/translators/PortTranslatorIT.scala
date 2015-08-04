/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, Subnet => SubnetType}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.packets.MAC
import org.midonet.packets.util.AddressConversions._
import org.midonet.util.concurrent.toFutureOps

/**
 * Provides integration tests for PortTranslator.
 */
@RunWith(classOf[JUnitRunner])
class PortTranslatorIT extends C3POMinionTestBase with ChainManager {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "Port translator" should " handle VIF port CRUD" in {
        // Create a network with two VIF ports.
        val nw1Id = UUID.randomUUID()
        val nw1Json = networkJson(nw1Id, "tenant", "network1")
        val (nw1p1Id, nw1p2Id) = (UUID.randomUUID(), UUID.randomUUID())
        val nw1p1Json = portJson(nw1p1Id, nw1Id)
        val nw1p2Json = portJson(nw1p2Id, nw1Id)

        insertCreateTask(2, NetworkType, nw1Json, nw1Id)
        insertCreateTask(3, PortType, nw1p1Json, nw1p1Id)
        insertCreateTask(4, PortType, nw1p2Json, nw1p2Id)

        // Create the host.
        val h1Id = UUID.randomUUID()
        createHost(h1Id)

        // Simulate mm-ctl binding the first port.
        eventually(bindVifPort(nw1p1Id, h1Id, "eth0"))
        eventually {
            val h = storage.get(classOf[Host], h1Id).await()
            h.getPortIdsList should contain only toProto(nw1p1Id)
        }

        // Simulate mm-ctl binding the second port.
        eventually(bindVifPort(nw1p2Id, h1Id, "eth1"))
        eventually {
            val h = storage.get(classOf[Host], h1Id).await()
            h.getPortIdsList should contain
                only(toProto(nw1p1Id), toProto(nw1p2Id))
        }

        // Update the first port. This should preserve the binding.
        val nw1p1DownJson = portJson(nw1p1Id, nw1Id, adminStateUp = false)
        insertUpdateTask(5, PortType, nw1p1DownJson, nw1p1Id)
        eventually {
            val hf = storage.get(classOf[Host], h1Id)
            val p = storage.get(classOf[Port], nw1p1Id).await()
            p.getHostId shouldBe toProto(h1Id)
            p.getInterfaceName shouldBe "eth0"
            hf.await().getPortIdsCount shouldBe 2
        }

        // Delete the second port.
        insertDeleteTask(6, PortType, nw1p2Id)
        eventually {
            val hf = storage.get(classOf[Host], h1Id)
            storage.exists(classOf[Port], nw1p2Id).await() shouldBe false
            hf.await().getPortIdsList should contain only toProto(nw1p1Id)
        }

        // Add allowed address pairs to the port. Use one with CIDR and one
        // without.
        val nw1p1WithAddrPairsJson = portJson(
            nw1p1Id, nw1Id, allowedAddrPairs = List(
                AddrPair("10.0.1.0/24", "ab:cd:ef:ab:cd:ef"),
                AddrPair("10.0.2.1", "12:34:56:12:34:56")))
        insertUpdateTask(7, PortType, nw1p1WithAddrPairsJson, nw1p1Id)
        eventually {
            val asc = storage.get(classOf[Chain],
                                  antiSpoofChainId(nw1p1Id)).await()
            asc.getRuleIdsCount shouldBe 5

            // First two rules (don't drop ARP, don't drop DHCP) and the last
            // one (drop everything) are fixed.
            val ruleIds = asc.getRuleIdsList.asScala.slice(2, 4)
            val rules = storage.getAll(classOf[Rule], ruleIds).await()
            rules(0).getAction shouldBe Action.RETURN
            rules(0).getNwSrcIp.getAddress shouldBe "10.0.1.0"
            rules(0).getNwSrcIp.getPrefixLength shouldBe 24
            rules(1).getAction shouldBe Action.RETURN
            rules(1).getNwSrcIp.getAddress shouldBe "10.0.2.1"
            rules(1).getNwSrcIp.getPrefixLength shouldBe 32
        }

        // Unbind the first port.
        unbindVifPort(nw1p1Id)
        eventually {
            val hf = storage.get(classOf[Host], h1Id)
            val p = storage.get(classOf[Port], nw1p1Id).await()
            p.hasHostId shouldBe false
            p.hasInterfaceName shouldBe false
            hf.await().getPortIdsCount shouldBe 0
        }
    }

    it should "seed Network's ARP table." in {
        val nw1Id = UUID.randomUUID()
        val nw1Json = networkJson(nw1Id, "tenant", "network1")
        val sn1Id = UUID.randomUUID()
        val sn1Json = subnetJson(id = sn1Id, nw1Id, cidr = "10.0.2.0/24")
        val vifPortId = UUID.randomUUID()
        val vifPortMac = "ad:be:cf:03:14:25"
        val vifPortIp = "10.0.2.5"
        insertCreateTask(2, NetworkType, nw1Json, nw1Id)
        insertCreateTask(3, SubnetType, sn1Json, sn1Id)

        // Create a legacy ReplicatedMap for the Network ARP table.
        val arpTable = dataClient.getIp4MacMap(nw1Id)
        val nw1 = eventually(storage.get(classOf[Network], nw1Id).await())
        nw1.getTenantId shouldBe "tenant"
        eventually(arpTable.start())

        arpTable.containsKey(vifPortIp) shouldBe false

        val vifPortJson = portJson(
                vifPortId, nw1Id, macAddr = vifPortMac,
                fixedIps = List(IPAlloc(vifPortIp, sn1Id)))
        insertCreateTask(4, PortType, vifPortJson, vifPortId)
        eventually {
            arpTable.containsKey(vifPortIp) shouldBe true
            arpTable.get(vifPortIp) shouldBe MAC.fromString(vifPortMac)
        }

        // Update the port with a new fixed IP.
        val vifPortIp2 = "10.0.2.6"
        val vifPortJsonNewIp = portJson(
            vifPortId, nw1Id, macAddr = vifPortMac,
            fixedIps = List(IPAlloc(vifPortIp2, sn1Id)))
        insertUpdateTask(5, PortType, vifPortJsonNewIp, vifPortId)
        eventually {
            arpTable.containsKey(vifPortIp) shouldBe false
            arpTable.containsKey(vifPortIp2) shouldBe true
            arpTable.get(vifPortIp2) shouldBe MAC.fromString(vifPortMac)
        }

        // Update the IP and MAC in a single update.
        val vifPortMac2 = "ad:be:cf:03:14:26"
        val vifPortJsonNewMac = portJson(
            vifPortId, nw1Id, macAddr = vifPortMac2,
            fixedIps = List(IPAlloc(vifPortIp, sn1Id)))
        insertUpdateTask(6, PortType, vifPortJsonNewMac, vifPortId)
        eventually {
            arpTable.containsKey(vifPortIp2) shouldBe false
            arpTable.containsKey(vifPortIp) shouldBe true
            arpTable.get(vifPortIp) shouldBe MAC.fromString(vifPortMac2)
        }

        // Delete the VIF port.
        insertDeleteTask(10, PortType, vifPortId)
        eventually{
            arpTable.containsKey(vifPortIp) shouldBe false
        }
    }

    private def bindVifPort(portId: UUID, hostId: UUID, ifName: String)
    : Port = {
        val port = storage.get(classOf[Port], portId).await().toBuilder
            .setHostId(hostId)
            .setInterfaceName(ifName)
            .build()
        storage.update(port)
        port
    }

    private def unbindVifPort(portId: UUID): Port = {
        val port = storage.get(classOf[Port], portId).await().toBuilder
            .clearHostId()
            .clearInterfaceName()
            .build()
        storage.update(port)
        port
    }
}
