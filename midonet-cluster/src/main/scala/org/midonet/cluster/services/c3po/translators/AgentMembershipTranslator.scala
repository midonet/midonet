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
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.AgentMembership
import org.midonet.cluster.services.c3po.C3POStorageManager.Update
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.concurrent.toFutureOps

/**
 * Translator for Neutron's Tunnel Zone Host.
 */
class AgentMembershipTranslator(storage: ReadOnlyStorage)
    extends Translator[AgentMembership]
            with TunnelZoneManager {
    /**
     * Translates a Create operation on Neutron's AgentMembership. Agent
     * Membership is translated into a mapping, HostToIp, under the default
     * TunnelZone in MidoNet.
     */
    override protected def translateCreate(membership: AgentMembership)
    : OperationList = {
        val tz = getNeutronDefaultTunnelZone(storage)
        val tzWithHost = tz.toBuilder
        tzWithHost.addHostsBuilder()
                  .setHostId(membership.getId)   // Membership ID == Host ID.
                  .setIp(membership.getIpAddress)
        // Sets a back reference. Arguably duplicate information, but it's
        // better if the framework take care of as much work as possible.
        tzWithHost.addHostIds(membership.getId)

        List(Update(tzWithHost.build))
    }

    /**
     * Update is not supported for AgentMembership.
     */
    override protected def translateUpdate(tzHost: AgentMembership) =
        throw new UnsupportedOperationException(
                "Agent Membership Update is not supported.")

    /**
     * Translates a Delete operation on Neutron's AgentMembership. Looks up a
     * Neutron AgentMembership and the default Tunnel Zone. Looks for a
     * corresponding HostToIp mapping with the Host ID and deletes it,
     */
    override protected def translateDelete(id: UUID)
    : OperationList = {
        val midoOps = new OperationListBuffer()
        val tz = getNeutronDefaultTunnelZone(storage)
        val membership = storage.get(classOf[AgentMembership], id).await()
        val hostId = membership.getId  // Membership ID is equal to Host ID.

        val hostToDelete = tz.getHostsList.asScala
                             .indexWhere(_.getHostId == hostId)
        if (hostToDelete < 0)
            throw new IllegalStateException(
                    s"No host mapping found for host ${fromProto(hostId)}")

        val hostIdToDel = tz.getHostIdsList.indexOf(hostId)
        midoOps += Update(tz.toBuilder.removeHosts(hostToDelete)
                                      .removeHostIds(hostIdToDel).build())

        midoOps.toList
    }
}