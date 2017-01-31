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
import org.midonet.cluster.models.Topology.Chain


/**
 * Contains router-related operations shared by multiple translators.
 */
trait RouterManager extends ChainManager {

    protected def checkOldRouterTranslation(tx: Transaction,
                                            routerId: UUID): Unit = {
        if (isOldRouterTranslation(tx, routerId)) {
            throw new IllegalStateException(
                s"Router $routerId must be updated to work properly with the " +
                s"newest version of Midonet. Please disable and re-enable " +
                s"SNAT on the router's gateway, and then disassociate and " +
                s"reassociate any Floating IPs associated with the router.")
        }
    }

    protected def isOldRouterTranslation(tx: Transaction,
                                         routerId: UUID): Boolean = {
        !tx.exists(classOf[Chain], floatSnatExactChainId(routerId))
    }
}
