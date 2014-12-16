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

package org.midonet.cluster.data.neutron

import java.util.UUID

import com.google.protobuf.Message

package object importer {

    /** Case classes representing Neutron tasks imported from NeutronDB. */
    sealed trait Task {
        val taskId: Int
    }

    case class Create(taskId: Int,
                      rsrcType: NeutronResourceType[_ <: Message],
                      json: String) extends Task
    case class Delete(taskId: Int,
                      rsrcType: NeutronResourceType[_ <: Message],
                      objId: UUID) extends Task
    case class Update(taskId: Int,
                      rsrcType: NeutronResourceType[_ <: Message],
                      json: String) extends Task
    case class Flush(taskId: Int) extends Task

    class Transaction(val id: String, val tasks: List[Task]) {
        val lastTaskId = tasks.last.taskId
        val isFlushTxn = tasks.size == 1 && tasks(0).isInstanceOf[Flush]
    }

}
