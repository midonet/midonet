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

import scala.concurrent.Promise

import org.mockito.Mockito.{mock, when}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Operation, Update}
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

    // For testing CRUD on the old ZK data structure (e.g. ARP table)
    private val zkRoot = "/test"
    protected val pathBldr: PathBuilder = new PathBuilder(zkRoot)

    protected def initMockStorage() {
        storage = mock(classOf[ReadOnlyStorage])
    }

    /* Mock exists and get on an instance of M with an ID, "id". */
    protected def bind[M](id: UUID, msg: M, clazz: Class[M] = null) {
        val exists = msg != null
        val classOfM = if (clazz != null) clazz
                       else msg.getClass.asInstanceOf[Class[M]]
        when(storage.exists(classOfM, id))
            .thenReturn(Promise.successful(exists).future)
        if (exists)
            when(storage.get(classOfM, id))
                .thenReturn(Promise.successful(msg).future)
        else
            when(storage.get(classOfM, id))
                .thenThrow(new NotFoundException(classOfM, id))
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
                .thenReturn(Promise.successful(exists).future)
            if (exists)
                when(storage.get(classOfM, id))
                    .thenReturn(Promise.successful(msg.asInstanceOf[M]).future)
            else
                when(storage.get(classOfM, id))
                    .thenThrow(new NotFoundException(classOfM, id))
        }
        when(storage.getAll(classOfM, ids))
                    .thenReturn(Promise.successful(msgs).future)
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
}