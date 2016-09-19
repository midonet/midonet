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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronLoggingResource
import org.midonet.cluster.models.Topology.LoggingResource
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Operation, Update}
import org.midonet.util.concurrent.toFutureOps

class LoggingResourceTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[NeutronLoggingResource] {

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(nlr: NeutronLoggingResource)
    : OperationList = {
        throw new UnsupportedOperationException(
            "Update LoggingResource not supported.")
    }

    override protected def translateUpdate(nlr: NeutronLoggingResource)
    : OperationList = {
        if (storage.exists(classOf[LoggingResource], nlr.getId).await()) {
            val oldLogRes = storage.get(classOf[LoggingResource], nlr.getId).await()
            val newLogRes = oldLogRes.toBuilder.setEnabled(nlr.getEnabled).build()
            List(Update(newLogRes))
        } else {
            log.warn(s"LoggingResource ${nlr.getId} has not yet been created")
            List()
        }
    }

    override protected def translateDelete(lrId: UUID)
    : OperationList = {
        List(Delete(classOf[LoggingResource], lrId))
    }
    override protected def retainHighLevelModel(op: Operation[NeutronLoggingResource])
    : List[Operation[NeutronLoggingResource]] = {
        op match {
            case Create(_) | Update(_, _) | Delete(_, _) => List()
            case _ => super.retainHighLevelModel(op)
        }
    }
}
