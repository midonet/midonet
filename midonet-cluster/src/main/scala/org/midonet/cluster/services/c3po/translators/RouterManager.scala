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

import java.util.{UUID => JUUID}

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.{ReadOnlyStorage, Transaction}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.util.concurrent.toFutureOps


/**
 * Contains router-related operations shared by multiple translators.
 */
trait RouterManager extends ChainManager {
    protected def storage: ReadOnlyStorage

    // REVISIT(yamamoto): Ideally we can upgrade translation online.
    // It would be a challenge to do it without disrupting agents, though.
    @Deprecated
    @throws[UnsupportedOperationException]
    protected def checkOldRouterTranslation(routerId: UUID): Unit = {
        val chainId = floatSnatExactChainId(routerId)

        if (!storage.exists(classOf[Chain], chainId).await()) {
            throw new UnsupportedOperationException(
                "Router ${routerId} has an old incompatible translation")
        }
    }

    // REVISIT(yamamoto): Ideally we can upgrade translation online.
    // It would be a challenge to do it without disrupting agents, though.
    @throws[UnsupportedOperationException]
    protected def checkOldRouterTranslation(tx: Transaction,
                                            routerId: UUID): Unit = {
        val chainId = floatSnatExactChainId(routerId)

        if (!tx.exists(classOf[Chain], chainId)) {
            throw new UnsupportedOperationException(
                "Router ${routerId} has an old incompatible translation")
        }
    }
}
