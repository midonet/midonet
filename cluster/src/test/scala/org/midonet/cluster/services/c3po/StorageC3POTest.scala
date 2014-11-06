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

package org.midonet.cluster.services.c3po

import java.util.{HashMap, Map => JMap}

import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.services.c3po.OpType.Create
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.neutron.NetworkTranslator

/**
 * Tests ZoomC3PO.
 */
@RunWith(classOf[JUnitRunner])
class StorageC3POTest extends FlatSpec with BeforeAndAfterAll {
    val storage = mock(classOf[Storage])
    var c3po = new StorageC3PO(storage)

    val networkId = UUIDUtil.randomUuidProto
    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val adminStateUp = true
    val neutronNetwork = NeutronNetwork.newBuilder
                                .setId(networkId)
                                .setTenantId(tenantId)
                                .setName(networkName)
                                .setAdminStateUp(adminStateUp)
                                .build

    override def beforeAll() {
        val translators: JMap[Class[_], ApiTranslator[_]] = new HashMap()
        translators.put(classOf[NeutronNetwork], new NetworkTranslator(storage))
        c3po.registerTranslators(translators)
    }

    "NeutronNetwork CREATE" should "call ZOOM.create with Mido Network" in {
        c3po.translate(Create, neutronNetwork)

        verify(storage).create(Network.newBuilder()
                                      .setId(networkId)
                                      .setTenantId(tenantId)
                                      .setName(networkName)
                                      .setAdminStateUp(adminStateUp)
                                      .build)
    }

    // TODO Add test for when a translator is not registered.
}
