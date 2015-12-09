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

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons
import org.midonet.cluster.services.c3po.C3POStorageManager.Operation
import org.midonet.cluster.services.c3po.OpType.OpType

package object midonet {
    // These below are required only for compatibility with old Replicated Maps.
    case class CreateNode(path: String, value: String = null)
        extends Operation[Nothing] {
        override val opType = OpType.CreateNode
        override def toPersistenceOp: PersistenceOp = CreateNodeOp(path, value)
    }

    case class UpdateNode(path: String, value: String) extends Operation[Nothing] {
        override val opType = OpType.UpdateNode
        override def toPersistenceOp: PersistenceOp = UpdateNodeOp(path, value)
    }

    case class DeleteNode(path: String) extends Operation[Nothing] {
        override def opType: OpType = OpType.DeleteNode
        override def toPersistenceOp: PersistenceOp = DeleteNodeOp(path)
    }
}
