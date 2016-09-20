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

import org.mockito.ArgumentMatcher
import org.mockito.Matchers.{any, anyBoolean}
import org.mockito.Mockito.{mock, never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage, Transaction}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Update}
import org.midonet.cluster.services.c3po.OpType
import org.midonet.midolman.state.PathBuilder

/**
 * Abstract base class for C3PO Translator unit test classes.
 */
abstract class TranslatorTestBase  extends FlatSpec
                                   with BeforeAndAfter
                                   with Matchers
                                   with BridgeStateTableManager {
    /* Each implementing unit test class initializes the (mock) storage by
     * calling initMockStorage() below. */
    protected var storage: ReadOnlyStorage = _
    protected var transaction: Transaction = _
    initMockStorage()


    // For testing CRUD on the old ZK data structure (e.g. ARP table)
    private val zkRoot = "/test"
    protected val pathBldr: PathBuilder = new PathBuilder(zkRoot)

    protected def initMockStorage() {
        storage = mock(classOf[ReadOnlyStorage])
        transaction = mock(classOf[Transaction])
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
        verify(transaction, never()).deleteNode(any(), anyBoolean())
    }
}

