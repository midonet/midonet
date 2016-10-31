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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Neutron.AgentMembership
import org.midonet.cluster.util.UUIDUtil.fromProto

/**
 * Translator for Neutron's Tunnel Zone Host.
 */
class AgentMembershipTranslator
    extends Translator[AgentMembership] with TunnelZoneManager {

    /**
     * Translates a Create operation on Neutron's AgentMembership. Agent
     * Membership is translated into a mapping, HostToIp, under the default
     * TunnelZone in MidoNet.
     */
    override protected def translateCreate(tx: Transaction,
                                           membership: AgentMembership)
    : OperationList = {
        val tunnelZone = getNeutronDefaultTunnelZone(tx)
        val tunnelZoneWithHost = tunnelZone.toBuilder
        tunnelZoneWithHost.addHostsBuilder()
            .setHostId(membership.getId)
            .setIp(membership.getIpAddress)

        // Sets a back reference. Arguably duplicate information, but it's
        // better if the framework take care of as much work as possible.
        tunnelZoneWithHost.addHostIds(membership.getId)
        tx.update(tunnelZoneWithHost.build())

        List()
    }

    /**
     * Update is not supported for AgentMembership.
     */
    override protected def translateUpdate(tx: Transaction,
                                           tzHost: AgentMembership) =
        throw new UnsupportedOperationException(
                "Agent membership update is not supported")

    /**
     * Translates a Delete operation on Neutron's AgentMembership. Looks up a
     * Neutron AgentMembership and the default Tunnel Zone. Looks for a
     * corresponding HostToIp mapping with the Host ID and deletes it,
     */
    override protected def translateDelete(tx: Transaction,
                                           membership: AgentMembership)
    : OperationList = {
        val tunnelZone = getNeutronDefaultTunnelZone(tx)
        val hostId = membership.getId

        val hostIndex =
            tunnelZone.getHostsList.asScala.indexWhere(_.getHostId == hostId)
        if (hostIndex < 0) {
            throw new IllegalStateException(
                s"No host mapping found for host ${fromProto(hostId)}")
        }

        val hostIdIndex = tunnelZone.getHostIdsList.indexOf(hostId)
        tx.update(tunnelZone.toBuilder.removeHosts(hostIndex)
                                      .removeHostIds(hostIdIndex).build())

        List()
    }
}