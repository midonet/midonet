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
import org.midonet.cluster.models.Neutron.TunnelZoneHost
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.util.concurrent.toFutureOps

/**
 * Translator for Neutron's Tunnel Zone Host.
 */
class TunnelZoneHostTranslator(storage: ReadOnlyStorage)
        extends NeutronTranslator[TunnelZoneHost] {
    /**
     * Translates a Create operation on Neutron's TunnelZoneHost. Tunnel Zone
     * Host is translated into a mapping, HostToIp, under a corresponding
     * TunnelZone in MidoNet.
     */
    override protected def translateCreate(tzHost: TunnelZoneHost)
    : MidoOpList = {
        val tz = storage.get(classOf[TunnelZone], tzHost.getTunnelZoneId)
                        .await()
        val tzWithHost = tz.toBuilder()
        tzWithHost.addHostsBuilder()
                  .setHostId(tzHost.getHostId)
                  .setIp(tzHost.getIpAddress)
        tzWithHost.addHostIds(tzHost.getHostId)  // Set a back reference.

        List(Update(tzWithHost.build))
    }

    /**
     * Update is not supported for TunnelZoneHost.
     */
    override protected def translateUpdate(tzHost: TunnelZoneHost)
    : MidoOpList =
        throw new TranslationException(
                neutron.Update(tzHost),
                msg = s"Update is not supported for ${classOf[TunnelZoneHost]}")

    /**
     * Translates a Delete operation on Neutron's TunnelZoneHost. Looks up a
     * Neutron TunnelZoneHost entry to retrieve the corresponding MidoNet
     * TunnelZone's ID. Looks for a corresponding HostToIp mapping with the Host
     * ID. If found, deletes it, and no op otherwise.
     */
    override protected def translateDelete(id: UUID)
    : MidoOpList = {
        val midoOps = new MidoOpListBuffer()
        val tzHost = storage.get(classOf[TunnelZoneHost], id).await()
        val tz = storage.get(classOf[TunnelZone], tzHost.getTunnelZoneId)
                        .await()

        val hostToDelete = tz.getHostsList.asScala
                             .indexWhere(_.getHostId == tzHost.getHostId)
        if (hostToDelete >= 0) {
            // Need to clear the back reference to Host as well.
            val hostIdToDelete = tz.getHostIdsList.asScala
                                   .indexOf(tzHost.getHostId)
            midoOps += Update(tz.toBuilder()
                                .removeHosts(hostToDelete)
                                .removeHostIds(hostIdToDelete).build())
        }

        midoOps.toList
    }

}