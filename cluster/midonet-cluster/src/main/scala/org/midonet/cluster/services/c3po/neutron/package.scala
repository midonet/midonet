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

import com.google.protobuf.Message

import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.services.c3po.C3POStorageManager.{OpType, Operation}

package object neutron {

    sealed trait NeutronOp[T <: Message] extends Operation

    case class Create[T <: Message](model: T) extends NeutronOp[T] {
        override val opType = OpType.Create
        override def toPersistenceOp = CreateOp(model)
    }

    case class Update[T <: Message](model: T) extends NeutronOp[T] {
        override val opType = OpType.Update
        override def toPersistenceOp = UpdateOp(model, null)
    }

    case class Delete[T <: Message](clazz: Class[T], id: Commons.UUID)
        extends NeutronOp[T] {
        override val opType = OpType.Delete
        /* C3PODataManager's deletion semantics is delete-if-exists by default
         * and no-op if the object doesn't exist. Revisit if we need to make
         * this configurable. */
        override def toPersistenceOp = DeleteOp(clazz, id,
                                                ignoreIfNotExists = true)
    }

    sealed case class Transaction(txnId: String,
                                  tasks: List[Task[_ <: Message]]) {
        def lastTaskId = tasks.last.taskId
    }

    sealed case class Task[T <: Message](taskId: Int, op: NeutronOp[T])

}
