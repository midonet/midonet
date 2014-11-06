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

package org.midonet.brain.services.c3po

import java.util.{HashMap, Map => JMap}

import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.brain.services.neutron.NetworkTranslator
import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.util.UUIDUtil

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

    "NeutronNetwork CREATE" should "call ZOOM.multi with CreateOp on " +
    "Mido Network" in {
        c3po.interpretAndExec(ModelCreate(neutronNetwork))

        verify(storage).multi(List(
                CreateOp(Network.newBuilder().setId(networkId)
                                             .setTenantId(tenantId)
                                             .setName(networkName)
                                             .setAdminStateUp(adminStateUp)
                                             .build)))
    }

    "NeutronNetwork Update" should "call ZOOM.multi with UpdateOp on " +
    "Mido Network" in {
        val newNetworkName = "neutron update test"
        c3po.interpretAndExec(ModelUpdate(
                neutronNetwork.toBuilder().setName(newNetworkName).build))

        verify(storage).multi(List(
                UpdateOp(Network.newBuilder().setId(networkId)
                                             .setTenantId(tenantId)
                                             .setName(newNetworkName)
                                             .setAdminStateUp(adminStateUp)
                                             .build)))
    }

    "NeutronNetwork Delete" should "call ZOOM.multi with DeleteOp on " +
    "Mido Network" in {
        c3po.interpretAndExec(ModelDelete(classOf[NeutronNetwork], networkId))

        verify(storage).multi(List(
                DeleteOp(classOf[Network], networkId)))
    }

    "Translate()" should "throw an exception when no corresponding " +
                         "translator has been registered" in {
        val e = intercept[TranslationException] {
            c3po.interpretAndExec(ModelCreate(networkId))
        }
    }
}
