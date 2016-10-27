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

import scala.concurrent.Future

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.{anyObject, argThat}
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FlatSpec}

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage.{PersistenceOp, Storage, StorageException, Transaction => ZoomTransaction}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronRoute}
import org.midonet.cluster.models.Topology.{Network, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.translators.{NetworkTranslator, TranslationException, Translator}
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

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

    var storage: Storage = _
    var storageManager: C3POStorageManager = _
    var transaction: ZoomTransaction = _
    var mockNetworkTranslator: Translator[NeutronNetwork] = _
    var mockPortTranslator: Translator[NeutronPort] = _
    var mockExtraTranslator: Translator[NeutronRoute] = _

    override def beforeEach() = {
        storage = mock(classOf[Storage])
        transaction = mock(classOf[ZoomTransaction])
        when(storage.transaction()).thenReturn(transaction)
        when(storage.get(classOf[C3POState], C3POState.ID))
            .thenReturn(Future.successful(C3POState.at(2)))

        mockNetworkTranslator = mock(classOf[Translator[NeutronNetwork]])
        mockPortTranslator = mock(classOf[Translator[NeutronPort]])
        mockExtraTranslator = mock(classOf[Translator[NeutronRoute]])
    }

    type TranslatorMap = Map[Class[_], Translator[_]]

    def buildManager(translatorMap: TranslatorMap = Map.empty): Unit = {
        val config = mock(classOf[ClusterConfig])
        val sequenceDispenser = mock(classOf[SequenceDispenser])
        val backend = mock(classOf[MidonetBackend])
        when(backend.store).thenReturn(storage)

        storageManager = new C3POStorageManager(config, backend,
                                                sequenceDispenser) {
            override def translatorOf(clazz: Class[_]): Option[Translator[_]] = {
                translatorMap.get(clazz)
            }
        }
        storageManager.init()

    }

    private def c3poCreate(taskId: Int, model: Message) =
        Task(taskId, Create(model))

    private def c3poUpdate(taskId: Int, model: Message) =
        Task(taskId, Update(model))

    private def c3poDelete(taskId: Int, clazz: Class[_ <: Message],
                           id: Commons.UUID) =
        Task(taskId, Delete(clazz, id))

    private def txn(txnId: String, task: Task[_ <: Message]*) =
        Transaction(txnId, task.toList)

    "C3POStorageManager" should "make sure C3POStageManager data exists in " +
    "Storage in initialization." in {
        buildManager()

        verify(storage).create(C3POState.at(0))
    }

    "NeutronNetwork CREATE" should "call ZOOM.multi with CreateOp on " +
    "Mido Network" in {
        buildManager(Map(classOf[NeutronNetwork] -> new NetworkTranslator()))

        storageManager.interpretAndExecTxn(
            txn("txn1", c3poCreate(2, neutronNetwork)))

        verify(transaction).create(neutronNetwork)
        verify(transaction).create(midoNetwork)
    }

    "Translate()" should "throw an exception when no corresponding " +
                         "translator has been registered" in {
        buildManager()

        intercept[ProcessingException] {
            storageManager.interpretAndExecTxn(
                    txn("txn1", c3poCreate(2, neutronNetwork)))
        }
    }

    "NeutronNetwork Update" should "call ZOOM.multi with UpdateOp on " +
    "Mido Network" in {
        buildManager(Map(classOf[NeutronNetwork] -> new NetworkTranslator()))

        val newNetworkName = "neutron update test"
        val updatedNetwork = neutronNetwork.toBuilder
                                           .setAdminStateUp(!adminStateUp)
                                           .setName(newNetworkName).build
        when(transaction.get(classOf[Network], networkId))
            .thenReturn(midoNetwork)

        storageManager.interpretAndExecTxn(
                txn("txn1", c3poUpdate(2, updatedNetwork)))

        verify(transaction).update(updatedNetwork, null)
        verify(transaction).update(midoNetwork.toBuilder.setName(newNetworkName)
                                       .setAdminStateUp(!adminStateUp)
                                       .build, null)
    }

    "NeutronNetwork Delete" should "call ZOOM.multi with DeleteOp on " +
    "Mido Network" in {
        buildManager(Map(classOf[NeutronNetwork] -> new NetworkTranslator()))

        when(transaction.get(classOf[NeutronNetwork], networkId))
            .thenReturn(NeutronNetwork.getDefaultInstance)

        storageManager.interpretAndExecTxn(
                txn("txn1", c3poDelete(2, classOf[NeutronNetwork], networkId)))

        verify(transaction).delete(classOf[NeutronNetwork], networkId,
                                   ignoresNeo = true)
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
                .translateOp(transaction, Create(neutronNetwork)))
                .thenReturn(List(Create(neutronNetwork),
                                 Create(midoNetwork)))
        when(mockPortTranslator
                .translateOp(transaction, Create(neutronNetworkPort)))
                .thenReturn(List(Create(neutronNetworkPort),
                                 Create(midoPort)))

        buildManager(Map(classOf[NeutronNetwork] -> mockNetworkTranslator,
                         classOf[NeutronPort] -> mockPortTranslator,
                         classOf[NeutronRoute] -> mockExtraTranslator))


        storageManager.interpretAndExecTxn(
                txn("txn1", c3poCreate(2, neutronNetwork),
                            c3poCreate(3, neutronNetworkPort)))

        verify(transaction).create(neutronNetwork)
        verify(transaction).create(midoNetwork)
        verify(transaction).update(C3POState.at(2), null)

        verify(transaction).create(neutronNetworkPort)
        verify(transaction).create(midoPort)
        verify(transaction).update(C3POState.at(3), null)

        verify(mockExtraTranslator, never()).translate(anyObject(), anyObject())
    }

    "Model translation failure" should "throw C3PODataManagerException" in {
        doThrow(new TranslationException(Create(neutronNetwork),
                                         null, "Translation failure test"))
            .when(mockNetworkTranslator)
            .translateOp(transaction, Create(neutronNetwork))

        buildManager(Map(classOf[NeutronNetwork] -> mockNetworkTranslator))

        intercept[ProcessingException] {
            storageManager.interpretAndExecTxn(
                    txn("txn1", c3poCreate(2, neutronNetwork)))
        }
    }

    "Storage failure" should "throw C3PODataManagerException" in {
        when(mockNetworkTranslator
                .translateOp(transaction, Create(neutronNetwork)))
                .thenReturn(List(Create(neutronNetwork), Create(midoNetwork)))
        doThrow(new StorageException("Storage failure test"))
                .when(transaction).create(anyObject())

        buildManager(Map(classOf[NeutronNetwork] -> mockNetworkTranslator))

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
