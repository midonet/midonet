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

import java.util
import java.util.{Map => JMap}

import scala.concurrent.{Future, Promise}

import com.google.protobuf.Message
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.{any, anyObject, argThat}
import org.mockito.Mockito.{doThrow, mock, never, verify, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FlatSpec}

import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, PersistenceOp, Storage, StorageException, UpdateOp}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronRoute}
import org.midonet.cluster.models.Topology.{Network, Port}
import org.midonet.cluster.services.c3po.midonet.Create
import org.midonet.cluster.services.c3po.translators.{NetworkTranslator, Translator, TranslationException}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.midolman.state.PathBuilder

object C3POStorageManagerTest {
    /* Matches with a list starting with specified PersistenceOps. */
    def startsWith(pOps: PersistenceOp*) = {
        argThat(new ArgumentMatcher[Seq[PersistenceOp]] {
            override def matches(list: Object) = {
                list.asInstanceOf[Seq[PersistenceOp]]
                    .startsWith(pOps)
            }
        })
    }
}

@RunWith(classOf[JUnitRunner])
class C3POStorageManagerTest extends FlatSpec with BeforeAndAfterEach {
    import org.midonet.cluster.services.c3po.C3POStorageManager._
    import org.midonet.cluster.services.c3po.C3POStorageManagerTest._
    val networkId = randomUuidProto
    val portId = randomUuidProto
    val tenantId = "neutron tenant"
    val networkName = "neutron test"
    val zkRoot = "/midonet/v2"
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
    val pathBldr = new PathBuilder(zkRoot)

    var storage: Storage = _
    var storageManager: C3POStorageManager = _
    var mockNetworkTranslator: Translator[NeutronNetwork] = _
    var mockPortTranslator: Translator[NeutronPort] = _
    var mockExtraTranslator: Translator[NeutronRoute] = _

    override def beforeEach() = {
        storage = mock(classOf[Storage])
        storageManager = new C3POStorageManager(storage)
        val statePromise = Promise.successful(C3POState.at(2))
        when(storage.get(classOf[C3POState], C3POState.ID))
            .thenReturn(statePromise.future)
        storageManager.init()

        mockNetworkTranslator = mock(classOf[Translator[NeutronNetwork]])
        mockPortTranslator = mock(classOf[Translator[NeutronPort]])
        mockExtraTranslator = mock(classOf[Translator[NeutronRoute]])
    }

    type TranslatorMap = JMap[Class[_], Translator[_]]

    def setUpNetworkTranslator() = {
        val translators: TranslatorMap = new util.HashMap()
        translators.put(classOf[NeutronNetwork],
                        new NetworkTranslator(storage, pathBldr))
        storageManager.registerTranslators(translators)
    }

    private def c3poCreate(taskId: Int, model: Message) =
        neutron.Task(taskId, neutron.Create(model))

    private def c3poUpdate(taskId: Int, model: Message) =
        neutron.Task(taskId, neutron.Update(model))

    private def c3poDelete(taskId: Int, clazz: Class[_ <: Message],
                           id: Commons.UUID) =
        neutron.Task(taskId, neutron.Delete(clazz, id))

    private def txn(txnId: String, task: neutron.Task[_ <: Message]*) =
        neutron.Transaction(txnId, task.toList)

    "C3POStorageManager" should "make sure C3POStageManager data exists in " +
    "Storage in initialization." in {
        val storage = mock(classOf[Storage])
        val manager = new C3POStorageManager(storage)
        when(storage.exists(classOf[C3POState], C3POState.ID))
            .thenReturn(Promise.successful(false).future)
        manager.init()

        verify(storage).create(C3POState.at(0))
    }

    "NeutronNetwork CREATE" should "call ZOOM.multi with CreateOp on " +
    "Mido Network" in {
        setUpNetworkTranslator()

        storageManager.interpretAndExecTxn(
                txn("txn1", c3poCreate(2, neutronNetwork)))

        verify(storage).multi(startsWith(
                CreateOp(neutronNetwork),
                CreateOp(midoNetwork)))
    }

    "Translate()" should "throw an exception when no corresponding " +
                         "translator has been registered" in {
        intercept[ProcessingException] {
            storageManager.interpretAndExecTxn(
                    txn("txn1", c3poCreate(2, neutronNetwork)))
        }
    }

    "NeutronNetwork Update" should "call ZOOM.multi with UpdateOp on " +
    "Mido Network" in {
        setUpNetworkTranslator()
        val newNetworkName = "neutron update test"
        val updatedNetwork = neutronNetwork.toBuilder
                                           .setAdminStateUp(!adminStateUp)
                                           .setName(newNetworkName).build
        when(storage.get(classOf[Network], networkId))
            .thenReturn(Future.successful(midoNetwork))

        storageManager.interpretAndExecTxn(
                txn("txn1", c3poUpdate(2, updatedNetwork)))

        verify(storage).multi(startsWith(
                UpdateOp(updatedNetwork),
                UpdateOp(midoNetwork.toBuilder().setName(newNetworkName)
                                                .setAdminStateUp(!adminStateUp)
                                                .build)))
    }

    "NeutronNetwork Delete" should "call ZOOM.multi with DeleteOp on " +
    "Mido Network" in {
        setUpNetworkTranslator()
        when(storage.get(classOf[NeutronNetwork], networkId))
            .thenReturn(Future.successful(NeutronNetwork.getDefaultInstance))

        storageManager.interpretAndExecTxn(
                txn("txn1", c3poDelete(2, classOf[NeutronNetwork], networkId)))

        verify(storage).multi(startsWith(
                DeleteOp(classOf[NeutronNetwork], networkId,
                         ignoreIfNotExists = true),
                DeleteOp(classOf[Network], networkId,
                         ignoreIfNotExists = true)))
    }

    /* TODO: not implemented yet

    "A flush request" should "call Storage.flush() and initialize " +
    "Storage Manager state" in {
        setUpNetworkTranslator()
        storageManager.flushTopology()

        verify(storage).flush()
        verify(storage, times(2)).create(C3POState.noTasksProcessed())
    }
    */

    // TODO: Actually, it should execute each transaction as a single multi
    // call. See comment in C3POStorageManager.interpretAndExecTxn.
    "Neutron transaction" should " execute each task as a separate " +
                                 " multi call." in {
        when(mockNetworkTranslator
                .translateNeutronOp(neutron.Create(neutronNetwork)))
                .thenReturn(List(Create(neutronNetwork),
                                 Create(midoNetwork)))
        when(mockPortTranslator
                .translateNeutronOp(neutron.Create(neutronNetworkPort)))
                .thenReturn(List(Create(neutronNetworkPort),
                                 Create(midoPort)))

        val translators: TranslatorMap = new util.HashMap()
        translators.put(classOf[NeutronNetwork], mockNetworkTranslator)
        translators.put(classOf[NeutronPort], mockPortTranslator)
        translators.put(classOf[NeutronRoute], mockExtraTranslator)
        storageManager.registerTranslators(translators)

        storageManager.interpretAndExecTxn(
                txn("txn1", c3poCreate(2, neutronNetwork),
                            c3poCreate(3, neutronNetworkPort)))

        verify(storage).multi(List(
                CreateOp(neutronNetwork),
                CreateOp(midoNetwork),
                UpdateOp(C3POState.at(2))))
        verify(storage).multi(List(
                CreateOp(neutronNetworkPort),
                CreateOp(midoPort),
                UpdateOp(C3POState.at(3))))
        verify(mockExtraTranslator, never()).translate(anyObject())
    }

    "Model translation failure" should "throw C3PODataManagerException" in {
        doThrow(new TranslationException(new neutron.Create(neutronNetwork),
                                         null, "Translation failure test"))
            .when(mockNetworkTranslator)
            .translateNeutronOp(neutron.Create(neutronNetwork))

        val translators: TranslatorMap = new util.HashMap()
        translators.put(classOf[NeutronNetwork], mockNetworkTranslator)
        storageManager.registerTranslators(translators)

        intercept[ProcessingException] {
            storageManager.interpretAndExecTxn(
                    txn("txn1", c3poCreate(2, neutronNetwork)))
        }
    }

    "Storage failure" should "throw C3PODataManagerException" in {
        when(mockNetworkTranslator
                .translateNeutronOp(neutron.Create(neutronNetwork)))
                .thenReturn(List(Create(neutronNetwork), Create(midoNetwork)))
        doThrow(new StorageException("Storage failure test"))
                .when(storage).multi(any(classOf[Seq[PersistenceOp]]))

        val translators: TranslatorMap = new util.HashMap()
        translators.put(classOf[NeutronNetwork], mockNetworkTranslator)
        storageManager.registerTranslators(translators)

        intercept[ProcessingException] {
            storageManager.interpretAndExecTxn(
                    txn("txn1", c3poCreate(2, neutronNetwork)))
        }
    }

    "C3PO Storage Mgr" should "return the last processed C3PO task ID." in {
        val lastProcessed = storageManager.lastProcessedTaskId

        assert(lastProcessed === 2, "last processed task ID.")
    }
}
