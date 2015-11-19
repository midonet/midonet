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
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.midonet
import org.midonet.cluster.services.c3po.midonet.CreateNode
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

    initMockStorage()
    val translator: NetworkTranslator = new NetworkTranslator(storage, pathBldr)

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
        val id = UUIDUtil.fromProto(sampleNetwork.getId)
        val midoOps = translator.translate(Create(sampleNeutronNetwork))
        val midoNetwork = Network.newBuilder().setId(sampleNetwork.getId)
                                              .setTenantId(tenantId)
                                              .setName(networkName)
                                              .setAdminStateUp(adminStateUp)
                                              .build()
        midoOps should contain only(
            Create(midoNetwork),
            CreateNode(pathBldr.getBridgeIP4MacMapPath(id), null),
            CreateNode(pathBldr.getBridgeMacPortsPath(id), null),
            CreateNode(pathBldr.getBridgeVlansPath(id), null))
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
        val newTenantId = "neutron tenant2"
        val newAdminStateUp = !adminStateUp
        bind(sampleNetwork.getId, sampleNetwork)
        val midoOps = translator.translate(
            Update(sampleNeutronNetwork.toBuilder
                               .setName(newName)
                               .setAdminStateUp(newAdminStateUp)
                               .setTenantId(newTenantId).build)
        )

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
        val id = genId()
        bind(id, NeutronNetwork.getDefaultInstance)
        val midoOps =
            translator.translate(Delete(classOf[NeutronNetwork], id))

        val juuid = UUIDUtil.fromProto(id)
        midoOps should contain only(
            Delete(classOf[Network], id),
            midonet.DeleteNode(pathBldr.getBridgeIP4MacMapPath(juuid)),
            midonet.DeleteNode(pathBldr.getBridgeMacPortsPath(juuid)),
            midonet.DeleteNode(pathBldr.getBridgeVlansPath(juuid)))

        // TODO Verify external network is also deleted.
    }

}
