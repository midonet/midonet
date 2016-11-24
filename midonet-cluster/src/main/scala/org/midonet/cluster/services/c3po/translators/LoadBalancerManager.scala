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

import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

/**
 * Contains loadbalancer-related operations shared by multiple translators.
 */
trait LoadBalancerManager {

    /** Deterministically generate loadbalancer ID from router ID. */
    protected def loadBalancerId(routerId: UUID) =
        routerId.xorWith(0xaab2c5dea7bc8deaL, 0x2ac7bb0371eee6d7L)

    /** Deterministically generate router ID from load balancer ID. */
    protected def lbRouterId(lbId: UUID) =
        lbId.xorWith(0xaab2c5dea7bc8deaL, 0x2ac7bb0371eee6d7L)

}
