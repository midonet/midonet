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
import org.midonet.cluster.models.Topology.{Dhcp, Route}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

import scala.collection.JavaConverters._

/**
 * Contains loadbalancer-related operations shared by multiple translators.
 */
trait LoadBalancerManager extends RouteManager {

    def buildRouterRoutesFromDhcp(portId: UUID, dhcp: Dhcp): List[Route] = {

        // A route for each of the host routes configured.
        dhcp.getOpt121RoutesList.asScala.map { r =>
            newNextHopPortRoute(
                portId,
                nextHopGateway = r.getGateway,
                dstSubnet = r.getDstSubnet)
        }.toList
    }

    protected def lbRouterInChainName(lbId: UUID) = s"LB_ROUTER_${lbId.asJava}"

    protected def lbRouterOutChainName(lbId: UUID) = s"LB_ROUTER_${lbId.asJava}"

    /** Deterministically generate loadbalancer ID from router ID. */
    protected def loadBalancerId(routerId: UUID) =
        routerId.xorWith(0xaab2c5dea7bc8deaL, 0x2ac7bb0371eee6d7L)

    /** Deterministically generate router ID from load balancer V2 ID.
      * This must be different from above, so that the router ID that gets
      * created will be different from the tenant router associated with older
      * V1 LBs, to prevent nasty conflicts. */
    protected def lbV2RouterId(lbId: UUID) =
        lbId.xorWith(0xed1225ed739945eaL, 0x8fd972a26f18632fL)

    protected def lbServiceContainerId(routerId: UUID) =
        routerId.xorWith(0xad1e25ee739d05eaL, 0x0ed972b36e19612fL)

    protected def lbServiceContainerGroupId(routerId: UUID): UUID =
        routerId.xorWith(0x7d263d2d55da46d2L, 0xaad282b46129613fL)

    protected def lbServiceContainerPortId(routerId: UUID): UUID =
        routerId.xorWith(0x1da81cddca3b9ff8L, 0xd966a652de3c9ccaL)

    protected def lbSnatRule(routerId: UUID): UUID =
        routerId.xorWith(0xe401ace2473413aaL, 0x7be4604b146962b6L)

    protected def lbRevSnatRule(routerId: UUID): UUID =
        routerId.xorWith(0xd9c8f6962541298dL, 0x7294e027de2b90baL)
}
