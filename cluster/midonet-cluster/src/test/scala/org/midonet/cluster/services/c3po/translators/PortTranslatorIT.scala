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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType.{Create, Delete, Update}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.packets.IPv4Subnet
import org.midonet.util.concurrent.toFutureOps

/**
 * Provides integration tests for PortTranslator.
 */
@RunWith(classOf[JUnitRunner])
class PortTranslatorIT extends C3POMinionTestBase {
    "Port translator" should " handle port bindings" in {
        // Create a host, a network, and two ports, one bound to the host.
        val nw1Id = UUID.randomUUID()
        val nw1Json = networkJson(nw1Id, "tenant", "network1").toString
        val h1Id = UUID.randomUUID()
        val (nw1p1Id, nw1p2Id) = (UUID.randomUUID(), UUID.randomUUID())
        val nw1p1Json = portJson(nw1p1Id, nw1Id,
                                 hostId = h1Id, ifName = "eth0").toString
        val nw1p2Json = portJson(nw1p2Id, nw1Id).toString

        createHost(h1Id)
        executeSqlStmts(
            insertTaskSql(2, Create, NetworkType, nw1Json, nw1Id, "tx1"),
            insertTaskSql(3, Create, PortType, nw1p1Json, nw1p1Id, "tx2"),
            insertTaskSql(4, Create, PortType, nw1p2Json, nw1p2Id, "tx3"))

        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p1Ftr = storage.get(classOf[Port], nw1p1Id)
            val (h1, p1) = (h1Ftr.await(), p1Ftr.await())
            h1.getPortIdsList.asScala should contain only p1.getId
            p1.getHostId shouldBe h1.getId
            p1.getInterfaceName shouldBe "eth0"
        }

        // Bind the second port.
        val nw1p2JsonV2 = portJson(nw1p2Id, nw1Id,
                                   hostId = h1Id, ifName = "eth1").toString
        executeSqlStmts(
            insertTaskSql(5, Update, PortType, nw1p2JsonV2, nw1p2Id, "tx4"))
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p2Ftr = storage.get(classOf[Port], nw1p2Id)
            val (h1, p2) = (h1Ftr.await(), p2Ftr.await())

            h1.getPortIdsList.asScala should
                contain only (p2.getId, toProto(nw1p1Id))
            p2.getHostId shouldBe h1.getId
            p2.getInterfaceName shouldBe "eth1"
        }

        // Unbind the first port.
        val nw1p1JsonV2 = portJson(nw1p1Id, nw1Id).toString
        executeSqlStmts(
            insertTaskSql(6, Update, PortType, nw1p1JsonV2, nw1p2Id, "tx5"))
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p1Ftr = storage.get(classOf[Port], nw1p1Id)
            val (h1, p1) = (h1Ftr.await(), p1Ftr.await())
            h1.getPortIdsList.asScala should contain only toProto(nw1p2Id)
            p1.hasHostId shouldBe false
            p1.hasInterfaceName shouldBe false
        }

        // Rebind the first port and delete the second port.
        executeSqlStmts(
            insertTaskSql(7, Update, PortType, nw1p1Json, nw1p1Id, "tx6"),
            insertTaskSql(8, Delete, PortType, "", nw1p2Id, "tx7"))
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p1Ftr = storage.get(classOf[Port], nw1p1Id)
            val (h1, p1) = (h1Ftr.await(), p1Ftr.await())
            h1.getPortIdsList.asScala should contain only toProto(nw1p1Id)
            p1.getHostId shouldBe h1.getId
            p1.getInterfaceName shouldBe "eth0"
        }

        // Delete the host.
        deleteHost(h1Id)
        eventually {
            val p1 = storage.get(classOf[Port], nw1p1Id).await()
            p1.hasHostId shouldBe false
            p1.getInterfaceName shouldBe "eth0"
        }

    }
}