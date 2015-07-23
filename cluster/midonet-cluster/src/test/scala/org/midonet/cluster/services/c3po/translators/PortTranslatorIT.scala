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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.util.concurrent.toFutureOps

/**
 * Provides integration tests for PortTranslator.
 */
@RunWith(classOf[JUnitRunner])
class PortTranslatorIT extends C3POMinionTestBase {
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
