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
package org.midonet.cluster.services.neutron

import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.{Router, Network}
import org.midonet.cluster.services.c3po.midonet
import org.midonet.cluster.services.c3po.neutron
import org.midonet.cluster.util.UUIDUtil

/**
 * Tests the Neutron Network model conversion layer.
 */
@RunWith(classOf[JUnitRunner])
class NetworkTranslatorTest extends FlatSpec with Matchers {
    val storage: ReadOnlyStorage = mock(classOf[ReadOnlyStorage])
    val translator: NetworkTranslator = new NetworkTranslator

    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val adminStateUp = true

    def genId = UUIDUtil.randomUuidProto

    val sampleNetwork = NeutronNetwork.newBuilder()
                                       .setId(genId)
                                       .setTenantId(tenantId)
                                       .setName(networkName)
                                       .setAdminStateUp(adminStateUp)
                                       .build

    "Network CREATE" should "produce Mido Network CREATE" in {
        val midoOps = translator.translate(neutron.Create(sampleNetwork))
        midoOps should contain only midonet.Create(Network.newBuilder()
                                                .setId(sampleNetwork.getId)
                                                .setTenantId(tenantId)
                                                .setName(networkName)
                                                .setAdminStateUp(adminStateUp)
                                                .build)
    }

    // TODO Test that NetworkTranslator ensures the provider router if
    // the network is external
    // TODO Test that NetworkTranslator creates and persists a new
    // tunnel key ID
    // TODO Test that NetworkTranslator updates the tunnel key ID node
    // with the data of the owner (Mido Network)
    // TODO Test that NetworkTranslator creates a tunnel key ID

    "Network UPDATE" should "produce a corresponding Mido Network UPDATE" in {
        val newName = "name2"
        val midoOps = translator.translate(
            neutron.Update(sampleNetwork.toBuilder.setName(newName).build)
        )

        midoOps should contain only midonet.Update(Network.newBuilder()
                                                .setId(sampleNetwork.getId)
                                                .setTenantId(tenantId)
                                                .setName(newName)
                                                .setAdminStateUp(adminStateUp)
                                                .build)

        // TODO Verify external network is updated.
    }

    "Network DELETE" should "produce a corresponding Mido Network DELETE" in {
        val id = genId
        val midoOps =
            translator.translate(neutron.Delete(classOf[NeutronNetwork], id))

        midoOps should contain only midonet.Delete(classOf[Network], id)

        // TODO Verify external network is also deleted.
    }

}
