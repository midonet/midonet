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

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Neutron.NeutronLoggingResource
import org.midonet.cluster.models.Topology.LoggingResource
import org.midonet.cluster.models.Topology.{Chain, RuleLogger, LoggingResource}
import org.midonet.cluster.models.Topology.LoggingResource.Type
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Update, Delete}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
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
        val oldLogRes = storage.get(classOf[LoggingResource], nlr.getId).await()
        val newLogRes = oldLogRes.toBuilder.setEnabled(nlr.getEnabled).build()
        List(Update(newLogRes))
    }

    override protected def translateDelete(nlr: NeutronLoggingResource)
    : OperationList = {
        val ops = new OperationListBuffer
        val lr = storage.get(classOf[LoggingResource], nlr.getId).await()
        ops ++= lr.getLoggerIdsList.asScala map (Delete(classOf[RuleLogger], _))
        ops += Delete(classOf[LoggingResource], nlr.getId)
        ops.toList
    }
}
