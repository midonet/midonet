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
import java.util.{UUID => JUUID}

import scala.concurrent.Promise

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Matchers.anyObject
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, PersistenceOp, UpdateOp}
import org.midonet.cluster.data.storage.{Storage, StorageException}
import org.midonet.cluster.models.C3PO.{C3POStorageManager => StorageManagerState}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronRoute
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.c3po.ApiTranslator
import org.midonet.cluster.services.c3po.C3PODataManagerException
import org.midonet.cluster.services.c3po.{C3POTask, C3POCreate, C3PODelete, C3POUpdate}
import org.midonet.cluster.services.c3po.MidoCreate
import org.midonet.cluster.services.c3po.OpType.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.TranslationException
import org.midonet.cluster.services.neutron.NetworkTranslator
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.UUIDUtil.toProto

/**
 * Tests ZoomC3PO.
 */
@RunWith(classOf[JUnitRunner])
class C3POStorageManagerTest extends FlatSpec with BeforeAndAfterEach {
    import C3POStorageManager.storageManagerState

    val storageManagerId = JUUID.randomUUID
    val networkId = randomUuidProto
    val portId = randomUuidProto
    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val adminStateUp = true
    val neutronNetwork = NeutronNetwork.newBuilder
                                       .setId(networkId)
                                       .setTenantId(tenantId)
                                       .setName(networkName)
                                       .setAdminStateUp(adminStateUp)
                                       .build
    val neutronNetworkPort = NeutronPort.newBuilder
                                        .setId(portId)
                                        .setNetworkId(networkId)
                                        .setTenantId(tenantId)
                                        .setAdminStateUp(adminStateUp)
                                        .build
    val midoNetwork = Network.newBuilder().setId(networkId)
                                          .setTenantId(tenantId)
                                          .setName(networkName)
                                          .setAdminStateUp(adminStateUp)
                                          .build
    val midoPort = Port.newBuilder().setId(portId)
                                    .setNetworkId(networkId)
                                    .setAdminStateUp(adminStateUp)
                                    .build

    var storage: Storage = _
    var storageManager: C3POStorageManager = _
    var mockNetworkTranslator: ApiTranslator[NeutronNetwork] = _
    var mockPortTranslator: ApiTranslator[NeutronPort] = _
    var mockExtraTranslator: ApiTranslator[NeutronRoute] = _

    override def beforeEach() = {
        storage = mock(classOf[Storage])
        storageManager = new C3POStorageManager(storageManagerId, storage)
        val stateMgrInitState = Promise[StorageManagerState]()
        stateMgrInitState.success(storageManagerState(storageManagerId, 0))
        when(storage.get(classOf[StorageManagerState],
                         toProto(storageManagerId)))
                .thenReturn(stateMgrInitState.future)
        storageManager.init()
        mockNetworkTranslator = mock(classOf[ApiTranslator[NeutronNetwork]])
        mockPortTranslator = mock(classOf[ApiTranslator[NeutronPort]])
        mockExtraTranslator = mock(classOf[ApiTranslator[NeutronRoute]])
    }

    def setUpNetworkTranslator() = {
        val translators: JMap[Class[_], ApiTranslator[_]] = new HashMap()
        translators.put(classOf[NeutronNetwork], new NetworkTranslator(storage))
        storageManager.registerTranslators(translators)
    }

    private def c3poCreate(taskId: Int, model: Object) =
        C3POTask(taskId, C3POCreate(model))

    private def c3poUpdate(taskId: Int, model: Object) =
        C3POTask(taskId, C3POUpdate(model))

    private def c3poDelete(taskId: Int, clazz: Class[_], id: Commons.UUID) =
        C3POTask(taskId, C3PODelete(clazz, id))

    "NeutronNetwork CREATE" should "call ZOOM.multi with CreateOp on " +
    "Mido Network" in {
        setUpNetworkTranslator()
        storageManager.interpretAndExec(c3poCreate(2, neutronNetwork))

        verify(storage).multi(List(
                CreateOp(Network.newBuilder().setId(networkId)
                                             .setTenantId(tenantId)
                                             .setName(networkName)
                                             .setAdminStateUp(adminStateUp)
                                             .build),
                anyObject))
    }

    "Translate()" should "throw an exception when no corresponding " +
                         "translator has been registered" in {
        intercept[C3PODataManagerException] {
            storageManager.interpretAndExec(c3poCreate(2, neutronNetwork))
        }
    }

    "NeutronNetwork Update" should "call ZOOM.multi with UpdateOp on " +
    "Mido Network" in {
        setUpNetworkTranslator()
        val newNetworkName = "neutron update test"
        storageManager.interpretAndExec(
                c3poUpdate(2, neutronNetwork.toBuilder()
                                            .setName(newNetworkName)
                                            .build))

        verify(storage).multi(List(
                UpdateOp(Network.newBuilder().setId(networkId)
                                             .setTenantId(tenantId)
                                             .setName(newNetworkName)
                                             .setAdminStateUp(adminStateUp)
                                             .build),
                anyObject))
    }

    "NeutronNetwork Delete" should "call ZOOM.multi with DeleteOp on " +
    "Mido Network" in {
        setUpNetworkTranslator()
        storageManager.interpretAndExec(
                c3poDelete(2, classOf[NeutronNetwork], networkId))

        verify(storage).multi(
                List(DeleteOp(classOf[Network], networkId), anyObject))
    }

    "Neutron transaction" should "produce a single equivalent ZOOM.multi " +
    "call." in {
        when(mockNetworkTranslator.toMido(C3POCreate(neutronNetwork)))
                .thenReturn(List(MidoCreate(midoNetwork)))
        when(mockPortTranslator.toMido(C3POCreate(neutronNetworkPort)))
                .thenReturn(List(MidoCreate(midoPort)))

        val translators: JMap[Class[_], ApiTranslator[_]] = new HashMap()
        translators.put(classOf[NeutronNetwork], mockNetworkTranslator)
        translators.put(classOf[NeutronPort], mockPortTranslator)
        translators.put(classOf[NeutronRoute], mockExtraTranslator)
        storageManager.registerTranslators(translators)

        storageManager.interpretAndExecTxn("txn1",
                List(c3poCreate(2, neutronNetwork),
                     c3poCreate(3, neutronNetworkPort)))

        verify(storage).multi(
                List(CreateOp(midoNetwork),
                     CreateOp(midoPort),
                     UpdateOp(storageManagerState(storageManagerId, 3))))
        verify(mockExtraTranslator, never()).toMido(anyObject())
    }

    "Model translation failure" should "throw C3PODataManagerException" in {
        doThrow(new TranslationException(Create,
                                         classOf[NeutronNetwork],
                                         "Translation failure test",
                                         null))
                .when(mockNetworkTranslator).toMido(C3POCreate(neutronNetwork))

        val translators: JMap[Class[_], ApiTranslator[_]] = new HashMap()
        translators.put(classOf[NeutronNetwork], mockNetworkTranslator)
        storageManager.registerTranslators(translators)

        intercept[C3PODataManagerException] {
            storageManager.interpretAndExecTxn(
                    "txn1", List(c3poCreate(2, neutronNetwork)))
        }
    }

    "Storage failure" should "throw C3PODataManagerException" in {
        when(mockNetworkTranslator.toMido(C3POCreate(neutronNetwork)))
                .thenReturn(List(MidoCreate(midoNetwork)))
        doThrow(new StorageException("Storage failure test"))
                .when(storage).multi(any(classOf[Seq[PersistenceOp]]))

        val translators: JMap[Class[_], ApiTranslator[_]] = new HashMap()
        translators.put(classOf[NeutronNetwork], mockNetworkTranslator)
        storageManager.registerTranslators(translators)

        intercept[C3PODataManagerException] {
            storageManager.interpretAndExecTxn(
                    "txn1", List(c3poCreate(2, neutronNetwork)))
        }
    }

    "C3PO Storage Mgr" should "return the last processed C3PO task ID." in {
        val statePromise = Promise[StorageManagerState]()
        statePromise.success(storageManagerState(storageManagerId, 2))

        when(storage.get(classOf[StorageManagerState],
                         toProto(storageManagerId)))
                .thenReturn(statePromise.future)
        val lastProcessed = storageManager.lastProcessedC3POTaskId()

        assert(lastProcessed === 2, "last processed task ID.")
    }
}
