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

import java.util.{Objects, UUID}

/**
 * Represents a VTEP's logical switch.
 * @param uuid is UUID of this switch in OVSDB
 * @param lsName is the name of the switch
 * @param tunnelKey is the VNI (null if not set)
 * @param lsDesc is the description associated to this switch
 */
final class LogicalSwitch(val uuid: UUID, lsName: String,
                          val tunnelKey: Integer, lsDesc: String) {
    val name = if (lsName == null || lsName.isEmpty) null else lsName
    val description = if (lsDesc == null || lsDesc.isEmpty) null else lsDesc

    private val str: String = "LogicalSwitch{" +
                                  "uuid=" + uuid + ", " +
                                  "name='" + name + "', " +
                                  "tunnelKey=" + tunnelKey + ", " +
                                  "description='" + description + "'}"
    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case null => false
        case ls: LogicalSwitch =>
            Objects.equals(name, ls.name) &&
            Objects.equals(description, ls.description) &&
            Objects.equals(tunnelKey, ls.tunnelKey)
        case other => false
    }

    override def hashCode: Int =
        if (uuid == null) Objects.hash(name, description, tunnelKey)
        else uuid.hashCode
}

object LogicalSwitch {
    def apply(id: UUID, name: String, vni: Integer, desc: String) =
        new LogicalSwitch(id, name, vni, desc)
    def apply(name: String, vni: Integer, desc: String) =
        new LogicalSwitch(null, name, vni, desc)
}
