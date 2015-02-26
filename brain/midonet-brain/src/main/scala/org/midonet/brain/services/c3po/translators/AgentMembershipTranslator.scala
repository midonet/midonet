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

package org.midonet.brain.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.brain.services.c3po.midonet.Update
import org.midonet.brain.services.c3po.neutron
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.AgentMembership
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.util.concurrent.toFutureOps

/**
 * Translator for Neutron's Tunnel Zone Host.
 */
class AgentMembershipTranslator(storage: ReadOnlyStorage)
        extends NeutronTranslator[AgentMembership] {
    /**
     * Translates a Create operation on Neutron's AgentMembership. Agent
     * Membership is translated into a mapping, HostToIp, under a corresponding
     * TunnelZone in MidoNet.
     */
    override protected def translateCreate(membership: AgentMembership)
    : MidoOpList = {
        val tz = getDefaultTunnelZone()
        val tzWithHost = tz.toBuilder()
        tzWithHost.addHostsBuilder()
                  .setHostId(membership.getId)   // Membership ID == Host ID.
                  .setIp(membership.getIpAddress)
        tzWithHost.addHostIds(membership.getId)  // Set a back reference.

        List(Update(tzWithHost.build))
    }

    /**
     * Update is not supported for AgentMembership.
     */
    override protected def translateUpdate(tzHost: AgentMembership)
    : MidoOpList =
        throw new TranslationException(
                neutron.Update(tzHost),
                msg = s"Update is not supported for ${classOf[AgentMembership]}")

    /**
     * Translates a Delete operation on Neutron's AgentMembership. Looks up a
     * Neutron AgentMembership entry to retrieve the corresponding MidoNet
     * TunnelZone's ID. Looks for a corresponding HostToIp mapping with the Host
     * ID. If found, deletes it, and no op otherwise.
     */
    override protected def translateDelete(id: UUID)
    : MidoOpList = {
        val midoOps = new MidoOpListBuffer()
        val tz = getDefaultTunnelZone()
        val membership = storage.get(classOf[AgentMembership], id).await()
        val hostId = membership.getId  // Membership ID is equal to Host ID.

        val hostToDelete = tz.getHostsList.asScala
                             .indexWhere(_.getHostId == hostId)
        if (hostToDelete >= 0) {
            // Need to clear the back reference to Host as well.
            val hostIdToDelete = tz.getHostIdsList.asScala.indexOf(hostId)
            midoOps += Update(tz.toBuilder()
                                .removeHosts(hostToDelete)
                                .removeHostIds(hostIdToDelete).build())
        }

        midoOps.toList
    }

    /* By spec, there must exist exactly one default tunnel zone. */
    private def getDefaultTunnelZone(): TunnelZone = {
        val tzs = storage.getAll(classOf[TunnelZone]).await()
        if (tzs.isEmpty)
            throw new RuntimeException("No tunnel zone's been configured.")
        else if (tzs.length > 1)
            throw new RuntimeException(
                    "Multiple tonnel zones exists and cannot finde default.")

        return tzs(0)
    }
}