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
package org.midonet.cluster.neutron

import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.models.Neutron
import org.midonet.cluster.util.UUIDUtil

/**
 * Tests the Neutron Network model conversion layer.
 */
@RunWith(classOf[JUnitRunner])
class NeutronNetworkTest extends FlatSpec {
    val storage: Storage = mock(classOf[Storage])
    val network: NeutronNetworkService = new NeutronNetworkService(storage)

    val networkId = UUIDUtil.randomUuidProto
    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val networkName2 = "neutron test2"
    val adminStateUp = true
    val neutronNetwork = Neutron.NeutronNetwork.newBuilder()
                                .setId(networkId)
                                .setTenantId(tenantId)
                                .setName(networkName)
                                .setAdminStateUp(adminStateUp)
                                .build

    "Netron Network CREATE" should "create a new Mido Network" in {
        network.create(neutronNetwork)

        verify(storage).create(Network.newBuilder()
                                      .setId(networkId)
                                      .setTenantId(tenantId)
                                      .setName(networkName)
                                      .setAdminStateUp(adminStateUp)
                                      .build)
    }

    // TODO Test that NeutronNetworkService ensures the provider router if
    // the network is external
    // TODO Test that NeutronNetworkService creates and persists a new
    // tunnel key ID
    // TODO Test that NeutronNetworkService updates the tunnel key ID node
    // with the data of the owner (Mido Network)
    // TODO Test that NeutronNetworkService creates a tunnel key ID

    "Netron Network UPDATE" should "update the corresponding Mido Network" in {
        val updatedNetwork = neutronNetwork.toBuilder()
                                           .setName(networkName2)
                                           .build
        network.update(updatedNetwork)
        verify(storage).update(Network.newBuilder()
                                      .setId(networkId)
                                      .setTenantId(tenantId)
                                      .setName(networkName2)
                                      .setAdminStateUp(adminStateUp)
                                      .build)

        // TODO Verify external network is updated.
    }

    "Netron Network DELETE" should "delete the corresponding Mido Network" in {
        network.delete(networkId)
        verify(storage).delete(classOf[Network], networkId)

        // TODO Verify external network is also deleted.
    }

    "Netron Network DELETE" should "be idempotent" in {
        doThrow(new NotFoundException(classOf[Network], networkId))
            .when(storage).delete(classOf[Network], networkId)
        network.delete(networkId)
    }
}