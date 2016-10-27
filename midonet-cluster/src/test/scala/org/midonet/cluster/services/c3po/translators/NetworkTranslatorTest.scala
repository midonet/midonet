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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil

/**
 * Tests the Neutron Network model conversion layer.
 */
@RunWith(classOf[JUnitRunner])
class NetworkTranslatorTest extends TranslatorTestBase {
    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val adminStateUp = true

    def genId() = UUIDUtil.randomUuidProto

    val translator: NetworkTranslator = new NetworkTranslator()

    val sampleNeutronNetwork = NeutronNetwork.newBuilder()
                                       .setId(genId())
                                       .setTenantId(tenantId)
                                       .setName(networkName)
                                       .setAdminStateUp(adminStateUp)
                                       .build()
    val sampleNetwork = Network.newBuilder()
                                       .setId(sampleNeutronNetwork.getId)
                                       .setAdminStateUp(adminStateUp)
                                       .setName(networkName)
                                       .setTenantId(tenantId)
                                       .build()

    "Network CREATE" should "produce Mido Network CREATE" in {
        initMockStorage()
        val id = UUIDUtil.fromProto(sampleNetwork.getId)
        translator.translate(transaction, Create(sampleNeutronNetwork))
        val midoNetwork = Network.newBuilder().setId(sampleNetwork.getId)
                                              .setTenantId(tenantId)
                                              .setName(networkName)
                                              .setAdminStateUp(adminStateUp)
                                              .build()
        midoOps should contain only Create(midoNetwork)
    }

    // TODO Test that NetworkTranslator ensures the provider router if
    // the network is external
    // TODO Test that NetworkTranslator creates and persists a new
    // tunnel key ID
    // TODO Test that NetworkTranslator updates the tunnel key ID node
    // with the data of the owner (Mido Network)
    // TODO Test that NetworkTranslator creates a tunnel key ID

    "Network UPDATE" should "produce a corresponding Mido Network UPDATE" in {
        initMockStorage()
        val newName = "name2"
        val newTenantId = "neutron tenant2"
        val newAdminStateUp = !adminStateUp
        bind(sampleNetwork.getId, sampleNetwork)
        translator.translate(transaction, Update(sampleNeutronNetwork.toBuilder
                                                     .setName(newName)
                                                     .setAdminStateUp(newAdminStateUp)
                                                     .setTenantId(newTenantId).build))

        // Test that name is updated but not tenant ID
        midoOps should contain only Update(Network.newBuilder()
                                                .setId(sampleNetwork.getId)
                                                .setTenantId(tenantId)
                                                .setName(newName)
                                                .setAdminStateUp(newAdminStateUp)
                                                .build)

        // TODO Verify external network is updated.
    }

    "Network DELETE" should "produce a corresponding Mido Network DELETE" in {
        initMockStorage()
        val id = genId()
        bind(id, NeutronNetwork.newBuilder.setId(id).build())
        translator.translate(transaction, Delete(classOf[NeutronNetwork], id))

        midoOps should contain only Delete(classOf[Network], id)

        // TODO Verify external network is also deleted.
    }

}
