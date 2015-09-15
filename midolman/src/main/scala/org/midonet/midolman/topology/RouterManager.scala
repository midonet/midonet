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
package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.{Set => ROSet}

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.BackChannelMessage
import org.midonet.midolman.layer3.{Route, RoutingTableIfc}
import org.midonet.midolman.simulation.Router
import org.midonet.midolman.simulation.Router.RoutingTable
import org.midonet.midolman.state.ArpCache
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr}

class RoutingTableWrapper[IP <: IPAddr](val rTable: RoutingTableIfc[IP])
    extends RoutingTable {

    /**
     * TODO (ipv6): de facto implementation for ipv4, that explains the casts at
     * this point.
     */
    def lookup(wmatch: FlowMatch): java.util.List[Route] =
        rTable.lookup(wmatch.getNetworkSrcIP.asInstanceOf[IP],
                      wmatch.getNetworkDstIP.asInstanceOf[IP])

    /**
     * TODO (ipv6): de facto implementation for ipv4, that explains the casts at
     * this point.
     */
    def lookup(wmatch: FlowMatch, logger: Logger): java.util.List[Route] =
        rTable.lookup(wmatch.getNetworkSrcIP.asInstanceOf[IP],
                      wmatch.getNetworkDstIP.asInstanceOf[IP],
                      logger.underlying)
}

object RouterManager {
    case class TriggerUpdate(cfg: Router.Config, arpCache: ArpCache,
                             rTable: RoutingTableWrapper[IPv4Addr])

    case class InvalidateFlows(routerId: UUID,
                               addedRoutes: ROSet[Route],
                               deletedRoutes: ROSet[Route]) extends BackChannelMessage

    // these msg are used for testing
    case class RouterInvTrieTagCountModified(dstIp: IPAddr, count: Int)

}

