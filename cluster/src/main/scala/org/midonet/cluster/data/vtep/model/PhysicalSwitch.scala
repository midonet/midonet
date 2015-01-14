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

package org.midonet.cluster.data.vtep.model

import java.util
import java.util.{Objects, UUID}

import org.midonet.packets.IPv4Addr

/**
 * Represents a VTEP's physical switch info.
 * @param uuid is UUID of this switch in OVSDB
 * @param name is the name of the switch
 * @param description is the description associated to this switch
 * @param ports is the list of physical port names in the VTEP
 * @param mgmtIps is the list of management ips for this physical switch
 * @param tunnelIps is set of tunnel ips (may be empty)
 */
final class PhysicalSwitch(val uuid: UUID, val name: String,
                           val description: String,
                           val ports: util.Collection[String],
                           val mgmtIps: util.Set[IPv4Addr],
                           val tunnelIps: util.Set[IPv4Addr]) {
    val tunnelIp: Option[IPv4Addr] =
        if (tunnelIps.isEmpty) None else Some(tunnelIps.iterator().next())
    val str: String = "PhysicalSwitch{" +
                          "uuid=" + uuid + ", " +
                          "name='" + name + "', " +
                          "description='" + description + "', " +
                          "ports=" + ports + ", " +
                          "mgmtIps=" + mgmtIps + ", " +
                          "tunnelIps=" + tunnelIps + "}"

    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: PhysicalSwitch =>
            Objects.equals(uuid, that.uuid) &&
            Objects.equals(name, that.name) &&
            Objects.equals(mgmtIps, that.mgmtIps) &&
            Objects.equals(tunnelIps, that.tunnelIps)
        case other => false
    }

    override def hashCode: Int = uuid.hashCode
}
