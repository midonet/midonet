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

import org.opendaylight.ovsdb.lib.notation.UUID

/**
 * Represents a VTEP's logical switch.
 * @param uuid is UUID of this switch in OVSDB
 * @param description is the description associated to this switch
 * @param name is the name of the switch
 * @param tunnelKey is the VNI
 */
final class LogicalSwitch(val uuid: UUID, val name: String,
                          val tunnelKey: Integer, val description: String) {
    override def toString: String = "LogicalSwitch{" +
                                    "uuid=" + uuid + ", " +
                                    "name=" + name + ", " +
                                    "tunnelKey=" + tunnelKey + ", " +
                                    "description=" + description + "}"

    override def equals(o: Any): Boolean = o match {
        case null => false
        case ls: LogicalSwitch =>
            Objects.equals(uuid, ls.uuid) &&
            Objects.equals(name, ls.name) &&
            Objects.equals(description, ls.description) &&
            Objects.equals(tunnelKey, ls.tunnelKey)
        case other => false
    }

    override def hashCode: Int = uuid.hashCode

}
