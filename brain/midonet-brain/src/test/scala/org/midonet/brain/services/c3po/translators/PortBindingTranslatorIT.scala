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

package org.midonet.brain.services.c3po.translators

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.brain.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, PortBinding => PortBindingType}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class PortBindingTranslatorIT extends C3POMinionTestBase {
    protected def portBindingJson(id: UUID,
                                  hostId: UUID,
                                  interfaceName: String,
                                  portId: UUID): JsonNode = {
        val pb = nodeFactory.objectNode
        pb.put("id", id.toString)
        pb.put("host_id", hostId.toString)
        pb.put("interface_name", interfaceName)
        pb.put("port_id", portId.toString)
        pb
    }

    it should "handle Port Binding" in {
        // Creates a Network.
        val network1Uuid = UUID.randomUUID()
        val network1Json = networkJson(network1Uuid, "tenant1", "private-net")
        executeSqlStmts(insertTaskSql(
                id = 2, Create, NetworkType, network1Json.toString,
                network1Uuid, "tx1"))

        // Creates a VIF port.
        val vifPortUuid = UUID.randomUUID()
        val vifPortId = toProto(vifPortUuid)
        val vifPortJson = portJson(name = "port1", id = vifPortUuid,
                                   networkId = network1Uuid).toString
        executeSqlStmts(insertTaskSql(
                id = 3, Create, PortType, vifPortJson, vifPortUuid, "tx2"))

        val vifPort = eventually(storage.get(classOf[Port], vifPortUuid).await())
        vifPort.hasHostId shouldBe false
        vifPort.hasInterfaceName shouldBe false

        // Sets up a host. Needs to do this directly via Zoom as the Host info
        // is to be created by the Agent.
        val hostId = UUID.randomUUID()
        val host = Host.newBuilder.setId(hostId).build()
        backend.ownershipStore.create(host, hostId)

        // Creates a Port Binding
        val bindingUuid = UUID.randomUUID()
        val interfaceName = "if1"
        val bindingJson = portBindingJson(bindingUuid,
                                          hostId,
                                          interfaceName,
                                          vifPortUuid).toString
        executeSqlStmts(insertTaskSql(
                id = 4, Create, PortBindingType, bindingJson, bindingUuid,
                "tx3"))

        // Tests that the host now has the binding to the port / interface.
        eventually {
            val boundHost = storage.get(classOf[Host], hostId).await()
            boundHost.getPortBindingsCount shouldBe 1
            val binding = boundHost.getPortBindings(0)
            binding.getInterfaceName shouldBe interfaceName
            binding.getPortId shouldBe toProto(vifPortUuid)
        }
        val boundPort = storage.get(classOf[Port], vifPortUuid).await()
        boundPort.getHostId shouldBe toProto(hostId)
        boundPort.getInterfaceName shouldBe interfaceName

        // Deletes the Port Binding
        executeSqlStmts(insertTaskSql(
                id = 5, Delete, PortBindingType, json = "", bindingUuid, "tx4"))
        eventually {
            val boundHost = storage.get(classOf[Host], hostId).await()
            boundHost.getPortBindingsCount shouldBe 0
        }
        val unbound = storage.get(classOf[Port], vifPortUuid).await()
        unbound.hasHostId shouldBe false
        unbound.hasInterfaceName shouldBe false
    }

}