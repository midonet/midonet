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

package org.midonet.vtep.model

import java.util.Objects
import java.util.Set

import org.midonet.packets.IPv4Addr
import org.opendaylight.ovsdb.lib.notation.UUID

/**
 * Represents a VTEP's physical switch info.
 * @param uuid is UUID of this switch in OVSDB
 * @param name is the name of the switch
 * @param description is the description associated to this switch
 * @param tunnelIps is set of tunnel ips (if any)
 */
final class PhysicalSwitch(val uuid: UUID, val name: String,
                           val description: String,
                           val tunnelIps: Set[IPv4Addr]) {
    val tunnelIp: Option[IPv4Addr] =
        if (tunnelIps.isEmpty) None else Some(tunnelIps.iterator().next())

    override def toString: String = "PhysicalSwitch{" +
                                    "uuid=" + uuid + ", " +
                                    "name=" + name + ", " +
                                    "description=" + description + ", " +
                                    "tunnelIps=" + tunnelIps + "}"

    override def equals(o: Any): Boolean = o match {
        case null => false
        case ps: PhysicalSwitch =>
            Objects.equals(uuid, ps.uuid) &&
            Objects.equals(name, ps.name) &&
            Objects.equals(description, ps.description)
        case other => false
    }

    override def hashCode: Int = uuid.hashCode
}
