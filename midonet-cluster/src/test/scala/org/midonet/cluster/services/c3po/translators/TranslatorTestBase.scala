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

import java.util.{UUID => JUUID}

import scala.concurrent.Future

import com.google.protobuf.Message

import org.mockito.ArgumentMatcher
import org.mockito.Matchers.any
import org.mockito.Mockito.{mock, never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.services.c3po.OpType
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.{IPv4Addr, MAC}

/**
 * Abstract base class for C3PO Translator unit test classes.
 */
abstract class TranslatorTestBase  extends FlatSpec
                                   with BeforeAndAfter
                                   with Matchers {
    /* Each implementing unit test class initializes the (mock) storage by
     * calling initMockStorage() below. */
    protected var storage: ReadOnlyStorage = _
    protected var stateTableStorage: StateTableStorage = _
    protected var transaction: Transaction = _
    protected var midoOps: OperationList = _
    initMockStorage()

    // For testing CRUD on the old ZK data structure (e.g. ARP table)
    private val zkRoot = "/test"
    protected val pathBldr: PathBuilder = new PathBuilder(zkRoot)

    protected def initMockStorage() {
        midoOps = List.empty
        storage = mock(classOf[ReadOnlyStorage])
        stateTableStorage = mock(classOf[StateTableStorage])
        transaction = mock(classOf[Transaction])
        when(transaction.create(any())).thenAnswer(
            new Answer[Unit] {
                override def answer(invocation: InvocationOnMock): Unit = {
                    midoOps = midoOps :+ Create(invocation.getArguments.apply(0)
                                                    .asInstanceOf[Message])
                }
            }
        )
        when(transaction.update(any(), any())).thenAnswer(
            new Answer[Unit] {
                override def answer(invocation: InvocationOnMock): Unit = {
                    midoOps = midoOps :+ Update(invocation.getArguments.apply(0)
                                                    .asInstanceOf[Message],
                                                invocation.getArguments.apply(1)
                                                    .asInstanceOf[UpdateValidator[Message]])
                }
            }
        )
        when(transaction.delete(any(), any(), any())).thenAnswer(
            new Answer[Unit] {
                override def answer(invocation: InvocationOnMock): Unit = {
                    midoOps = midoOps :+ Delete(invocation.getArguments.apply(0)
                                                    .asInstanceOf[Class[_ <: Message]],
                                                invocation.getArguments.apply(1)
                                                    .asInstanceOf[UUID])
                }
            }
        )
        when(transaction.createNode(any(), any())).thenAnswer(
            new Answer[Unit] {
                override def answer(invocation: InvocationOnMock): Unit = {
                    midoOps = midoOps :+ CreateNode(invocation.getArguments.apply(0)
                                                        .asInstanceOf[String],
                                                    invocation.getArguments.apply(1)
                                                        .asInstanceOf[String])
                }
            }
        )
        when(transaction.deleteNode(any(), any())).thenAnswer(
            new Answer[Unit] {
                override def answer(invocation: InvocationOnMock): Unit = {
                    midoOps = midoOps :+ DeleteNode(invocation.getArguments.apply(0)
                                                        .asInstanceOf[String])
                }
            }
        )
        when(stateTableStorage.bridgeMacTablePath(any[JUUID](),
                                                  any[Short]())).thenAnswer(
            new Answer[String] {
                override def answer(invocation: InvocationOnMock): String = {
                    val id = invocation.getArguments()(0).asInstanceOf[JUUID]
                    val vlanId = invocation.getArguments()(1).asInstanceOf[Short]
                    s"$zkRoot/0/tables/Network/$id/vlans/$vlanId/mac_port_table"
                }
            }
        )
        when(stateTableStorage.bridgeMacEntryPath(any[JUUID](),
                                                  any[Short](),
                                                  any[MAC](),
                                                  any[JUUID]())).thenAnswer(
            new Answer[String] {
                override def answer(invocation: InvocationOnMock): String = {
                    val id = invocation.getArguments()(0).asInstanceOf[JUUID]
                    val vlanId = invocation.getArguments()(1).asInstanceOf[Short]
                    val mac = invocation.getArguments()(2).asInstanceOf[MAC]
                    val portId = invocation.getArguments()(3).asInstanceOf[JUUID]
                    s"$zkRoot/0/tables/Network/$id/vlans/$vlanId/" +
                    s"mac_port_table/$mac,$portId,${Int.MaxValue}"
                }
            }
        )
        when(stateTableStorage.bridgeArpTablePath(any[JUUID]())).thenAnswer(
            new Answer[String] {
                override def answer(invocation: InvocationOnMock): String = {
                    val id = invocation.getArguments()(0).asInstanceOf[JUUID]
                    s"$zkRoot/0/tables/Network/$id/ip4_mac_table"
                }
            }
        )
        when(stateTableStorage.bridgeArpEntryPath(any[JUUID](),
                                                  any[IPv4Addr](),
                                                  any[MAC]())).thenAnswer(
            new Answer[String] {
                override def answer(invocation: InvocationOnMock): String = {
                    val id = invocation.getArguments()(0).asInstanceOf[JUUID]
                    val ip = invocation.getArguments()(1).asInstanceOf[IPv4Addr]
                    val mac = invocation.getArguments()(2).asInstanceOf[MAC]
                    s"$zkRoot/0/tables/Network/$id/mac_port_table" +
                    s"/$ip,$mac,${Int.MaxValue}"
                }
            }
        )
        when(stateTableStorage.fip64EntryPath(any[Fip64Entry]())).thenAnswer(
            new Answer[String] {
                override def answer(invocation: InvocationOnMock): String = {
                    val entry = invocation.getArguments()(0).asInstanceOf[Fip64Entry]
                    s"$zkRoot/0/tables/global/fip64_table/" + entry.encode
                }
            }
        )
    }

    /* Mock exists and get on an instance of M with an ID, "id". */
    protected def bind[M](id: UUID, msg: M, clazz: Class[M] = null) {
        val exists = msg != null
        val classOfM = if (clazz != null) clazz
                       else msg.getClass.asInstanceOf[Class[M]]
        when(storage.exists(classOfM, id))
            .thenReturn(Future.successful(exists))
        when(transaction.exists(classOfM, id)).thenReturn(exists)
        if (exists) {
            when(storage.get(classOfM, id))
                .thenReturn(Future.successful(msg))
            when(transaction.get(classOfM, id)).thenReturn(msg)
        }
        else {
            when(storage.get(classOfM, id))
                .thenThrow(new NotFoundException(classOfM, id))
            when(transaction.get(classOfM, id))
                .thenThrow(new NotFoundException(classOfM, id))
        }
    }

    /* Mock exists and getAll on instances of M with its ID in "ids".
     * msgs should NOT be null. */
    protected def bindAll[M](
            ids: Seq[UUID], msgs: Seq[M], clazz: Class[M] = null) {
        // We may bind null to a non-null ID, but not vice versa.
        assert(ids.size >= msgs.size)
        val classOfM = if (clazz != null) clazz
                       else msgs(0).getClass.asInstanceOf[Class[M]]
        for ((id, msg) <- ids.zipAll(msgs, null, null)) {
            val exists = msg != null
            when(storage.exists(classOfM, id))
                .thenReturn(Future.successful(exists))
            when(transaction.exists(classOfM, id))
                .thenReturn(exists)
            if (exists) {
                when(storage.get(classOfM, id))
                    .thenReturn(Future.successful(msg.asInstanceOf[M]))
                when(transaction.get(classOfM, id))
                    .thenReturn(msg.asInstanceOf[M])
            } else {
                when(storage.get(classOfM, id))
                    .thenThrow(new NotFoundException(classOfM, id))
                when(transaction.get(classOfM, id))
                    .thenThrow(new NotFoundException(classOfM, id))
            }
        }
        when(storage.getAll(classOfM, ids))
            .thenReturn(Future.successful(msgs))
        when(transaction.getAll(classOfM, ids)).thenReturn(msgs)
    }

    /* Return a matcher that match if a sequence (thatSeq) have all the elements
     * (and only that elements) of the sequence given as argument (thisSeq) */
    protected def seqInAnyOrder[T](thisSeq: Seq[T]) = new ArgumentMatcher[Seq[T]] {
        override def matches(thatSeq: scala.Any): Boolean =
            thisSeq.toSet.equals(thatSeq.asInstanceOf[Seq[T]].toSet)
    }

    /* Mock exists and getAll on instances of M with its ID in "ids" in any
     * order.
     * ids and msgs must NOT be null
     * ids and msgs must have the same number of elements */
    protected def bindAllInAnyOrder[K, M](
            ids: Seq[UUID], msgs: Seq[M], clazz: Class[M] = null): Unit = {
        assert(ids != null)
        assert(msgs != null)
        assert(ids.size == msgs.size)
        when(storage.getAll(org.mockito.Matchers.eq(clazz),
                            org.mockito.Matchers.argThat(seqInAnyOrder(ids))))
            .thenAnswer(
                new Answer[Future[Seq[M]]] {
                    val boundIds = (ids zip msgs).toMap
                    override def answer(invocation: InvocationOnMock) = {
                        val args = invocation.getArguments
                        val answerIds = args(1).asInstanceOf[Seq[UUID]]
                        Future.successful(answerIds map boundIds)
                    }
                }
            )
        when(transaction.getAll(org.mockito.Matchers.eq(clazz),
                                org.mockito.Matchers.argThat(seqInAnyOrder(ids))))
            .thenAnswer(
                new Answer[Seq[M]] {
                    val boundIds = (ids zip msgs).toMap
                    override def answer(invocation: InvocationOnMock) = {
                        val args = invocation.getArguments
                        val answerIds = args(1).asInstanceOf[Seq[UUID]]
                        answerIds map boundIds
                    }
                }
            )
    }

    /* Finds an operation on Chain with the specified chain ID, and returns a
     * first one found.
     */
    protected def findChainOp(ops: OperationList,
                              op: OpType.OpType,
                              chainId: UUID) = {
        ops.collectFirst {
            case Create(c: Chain)
                if c.getId == chainId && op == OpType.Create => c
            case Update(c: Chain, _)
                if c.getId == chainId && op == OpType.Update => c
        }.orNull
    }

    protected def verifyNoOp(transaction: Transaction): Unit = {
        verify(transaction, never()).create(any())
        verify(transaction, never()).update(any(), any())
        verify(transaction, never()).delete(any(), any(), any())
        verify(transaction, never()).createNode(any(), any())
        verify(transaction, never()).updateNode(any(), any())
        verify(transaction, never()).deleteNode(any(), any())
    }
}