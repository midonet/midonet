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
import org.junit.Assert.assertThat
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.data.storage.ObjectExistsException
import org.midonet.cluster.data.storage.ServiceUnavailableException
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.neutron.OpType.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil

/**
 * Tests the Neutron Network model conversion layer.
 */
@RunWith(classOf[JUnitRunner])
class NetworkTranslatorTest extends FlatSpec {
    val storage: Storage = mock(classOf[Storage])
    val network: NetworkTranslator = new NetworkTranslator(storage)

    val networkId = UUIDUtil.randomUuidProto
    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val networkName2 = "neutron test2"
    val adminStateUp = true
    val neutronNetwork = NeutronNetwork.newBuilder()
                                       .setId(networkId)
                                       .setTenantId(tenantId)
                                       .setName(networkName)
                                       .setAdminStateUp(adminStateUp)
                                       .build

    "Network CREATE" should "produce Mido Network CREATE" in {
        val midoOps: List[MidoModelOp] = network.toMido(Create, neutronNetwork)

        assert(midoOps.size == 1)
        assert(midoOps(0) == MidoModelOp(Create,
                                         Network.newBuilder()
                                                .setId(networkId)
                                                .setTenantId(tenantId)
                                                .setName(networkName)
                                                .setAdminStateUp(adminStateUp)
                                                .build))
    }

    // TODO Test that NeutronNetworkService ensures the provider router if
    // the network is external
    // TODO Test that NeutronNetworkService creates and persists a new
    // tunnel key ID
    // TODO Test that NeutronNetworkService updates the tunnel key ID node
    // with the data of the owner (Mido Network)
    // TODO Test that NeutronNetworkService creates a tunnel key ID

    "Network UPDATE" should "produce a corresponding Mido Network UPDATE" in {
        val midoOps: List[MidoModelOp] =
                network.toMido(Update, neutronNetwork.toBuilder()
                                                     .setName(networkName2)
                                                     .build)

        assert(midoOps.size == 1)
        assert(midoOps(0) == MidoModelOp(Update,
                                         Network.newBuilder()
                                                .setId(networkId)
                                                .setTenantId(tenantId)
                                                .setName(networkName2)
                                                .setAdminStateUp(adminStateUp)
                                                .build))

        // TODO Verify external network is updated.
    }

    "Network DELETE" should "produce a corresponding Mido Network DELETE" in {
        val midoOps: List[MidoModelOp] = network.toMido(Delete, neutronNetwork)

        assert(midoOps.size == 1)
        assert(midoOps(0) == MidoModelOp(Delete,
                                         Network.newBuilder()
                                                .setId(networkId)
                                                .setTenantId(tenantId)
                                                .setName(networkName)
                                                .setAdminStateUp(adminStateUp)
                                                .build))

        // TODO Verify external network is also deleted.
    }
}
