/*
 * Copyright 2016 Midokura SARL
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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronLoggingResource
import org.midonet.cluster.models.Topology.LoggingResource
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.util.UUIDUtil._

class LoggingResourceTranslator extends Translator[NeutronLoggingResource] {

    override protected def translateCreate(tx: Transaction,
                                           loggingResource: NeutronLoggingResource)
    : Unit = {
        throw new UnsupportedOperationException(
            "Creating a LoggingResource is not supported.")
    }

    override protected def translateUpdate(tx: Transaction,
                                           loggingResource: NeutronLoggingResource)
    : Unit = {
        if (tx.exists(classOf[LoggingResource], loggingResource.getId)) {
            val oldLoggingResource =
                tx.get(classOf[LoggingResource], loggingResource.getId)
            tx.update(oldLoggingResource.toBuilder
                          .setEnabled(loggingResource.getEnabled).build())
        } else {
            log.warn(s"LoggingResource ${loggingResource.getId.asJava} does " +
                     s"not exist")
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           loggingResourceId: UUID)
    : Unit = {
        tx.delete(classOf[LoggingResource], loggingResourceId, ignoresNeo = true)
    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronLoggingResource])
    : List[Operation[NeutronLoggingResource]] = {
        List()
    }
}
