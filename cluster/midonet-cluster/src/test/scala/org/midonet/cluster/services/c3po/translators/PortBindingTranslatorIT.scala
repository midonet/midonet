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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, PortBinding => PortBindingType}
import org.midonet.cluster.models.Topology._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class PortBindingTranslatorIT extends C3POMinionTestBase {
    it should "handle Port Binding" in {
        // Creates a Network.
        val network1Uuid = UUID.randomUUID()
        val network1Json = networkJson(network1Uuid, "tenant1", "private-net")
        insertCreateTask(2, NetworkType, network1Json, network1Uuid)

        // Creates a VIF port.
        val vifPortUuid = UUID.randomUUID()
        val vifPortJson = portJson(name = "port1", id = vifPortUuid,
                                   networkId = network1Uuid)
        insertCreateTask(3, PortType, vifPortJson, vifPortUuid)

        val vifPort = eventually(storage.get(classOf[Port], vifPortUuid).await())
        vifPort.hasHostId shouldBe false
        vifPort.hasInterfaceName shouldBe false

        // Sets up a host. Needs to do this directly via Zoom as the Host info
        // is to be created by the Agent.
        val hostId = UUID.randomUUID()
        createHost(hostId)

        // Creates a Port Binding
        val bindingUuid = UUID.randomUUID()
        val interfaceName = "if1"
        val bindingJson = portBindingJson(bindingUuid,
                                          hostId,
                                          interfaceName,
                                          vifPortUuid)
        insertCreateTask(4, PortBindingType, bindingJson, bindingUuid)

        // Tests that the host now has the binding to the port / interface.
        eventually(checkPortBinding(hostId, vifPortUuid, interfaceName))

        // Deletes the Port Binding
        insertDeleteTask(5, PortBindingType, bindingUuid)
        eventually {
            val hostFtr = storage.get(classOf[Host], hostId)
            val portFtr = storage.get(classOf[Port], vifPortUuid)
            val (host, port) = (hostFtr.await(), portFtr.await())
            host.getPortIdsCount shouldBe 0
            port.hasHostId shouldBe false
            port.hasInterfaceName shouldBe false
        }
    }

}