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

import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Operation, Update}
import org.midonet.util.concurrent.toFutureOps

class IPSecSiteConnectionTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[IPSecSiteConnection] {

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(cnxn: IPSecSiteConnection)
    : OperationList = {
        List()
    }

    override protected def translateDelete(id: UUID): OperationList = {
        // retainHighLevelModel() should delete the IPSecSiteConnection, and
        // this should cascade to the routes due to Zoom bindings.
        List()
    }

    override protected def translateUpdate(cnxn: IPSecSiteConnection)
    : OperationList = {
        // No Midonet-specific changes, but changes to the IPSecSiteConnection
        // are handled in retainHighLevelModel()
        List()
    }

    /* Keep the original model as is by default. Override if the model does not
     * need to be maintained, or need some special handling. */
    override protected def retainHighLevelModel(
        op: Operation[IPSecSiteConnection])
    : List[Operation[IPSecSiteConnection]] = op match {
        case Update(cnxn, _) =>
            // Override update to make sure only certain fields are update,
            // to avoid overwriting route_ids, which Neutron doesn't know about.
            val oldCnxn = storage.get(classOf[IPSecSiteConnection],
                                      cnxn.getId).await()
            val newCnxn = cnxn.toBuilder()
                .addAllRouteIds(oldCnxn.getRouteIdsList()).build()
            List(Update(newCnxn))
        case _ => super.retainHighLevelModel(op)
    }
}

