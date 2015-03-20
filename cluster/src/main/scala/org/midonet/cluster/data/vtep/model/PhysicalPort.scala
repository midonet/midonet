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

package org.midonet.cluster.data.vtep.model

import java.util
import java.util.{Objects, UUID}

/**
 * Represents a physical port in a physical switch. Note that the maps and sets
 * may be empty, but not null.
 * Due to some ovsdb specifications being unclear on using null values or
 * empty strings to indicate unset string values, this class unifies both
 * representations. Note that 'name', as an identifier, is not allowed to
 * be null.
 * @param uuid is UUID of this locator in OVSDB
 * @param ppName physical port name
 * @param desc physical port description
 * @param bindings a map with a pair (Vlan key -> LogicalSwitch id) for each
 *                 binding
 * @param stats a map with a pair (Vlan key -> stats info id) for each
 *              binding, if supported by switch
 * @param faultStatus (undocumented feature from ovsdb)
 */
final class PhysicalPort(val uuid: UUID, ppName: String, desc: String,
                         bindings: util.Map[Integer, UUID],
                         stats: util.Map[Integer, UUID],
                         faultStatus: util.Set[String]) {
    val name: String = if (ppName == null) "" else ppName
    val description: String = if (desc == null || desc.isEmpty) null else desc
    val vlanBindings: util.Map[Integer, UUID] =
        if (bindings == null) new util.HashMap() else bindings
    val vlanStats: util.Map[Integer, UUID] =
        if (stats == null) new util.HashMap() else stats
    val portFaultStatus: util.Set[String] =
        if (faultStatus == null) new util.HashSet() else faultStatus

    private val str: String = "PhysicalPort{" +
        "uuid=" + uuid + ", " +
        "name='" + name + "', " +
        "description='" + description + "'}"

    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: PhysicalPort => Objects.equals(name, that.name)
        case other => false
    }

    override def hashCode: Int =
        if (uuid == null) name.hashCode else uuid.hashCode()
}

object PhysicalPort {
    def apply(id: UUID, name: String, desc: String,
              bindings: util.Map[Integer, UUID], stats: util.Map[Integer, UUID],
              faultStatus: util.Set[String]): PhysicalPort =
        new PhysicalPort(id, name, desc, bindings, stats, faultStatus)

    def apply(name: String, desc: String): PhysicalPort =
        apply(null, name, desc, null, null, null)
}


