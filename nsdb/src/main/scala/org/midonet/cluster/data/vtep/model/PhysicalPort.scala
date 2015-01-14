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

import scala.collection.JavaConversions.{asScalaSet, mapAsScalaMap}

/**
 * Represents a physical port in a physical switch. Note that the maps and sets
 * may be empty, but not null.
 * Due to some ovsdb specifications being unclear on using null values or
 * empty strings to indicate unset string values, this class unifies both
 * representations. Note that 'name', as an identifier, is not allowed to
 * be null.
 * @param id is UUID of this locator in OVSDB
 * @param ppName physical port name
 * @param desc physical port description
 * @param bindings a map with a pair (Vlan key -> LogicalSwitch id) for each
 *                 binding
 * @param stats a map with a pair (Vlan key -> stats info id) for each
 *              binding, if supported by switch
 * @param faultStatus (undocumented feature from ovsdb)
 */
final class PhysicalPort(id: UUID, ppName: String, desc: String,
                         bindings: Map[Integer, UUID],
                         stats: Map[Integer, UUID],
                         faultStatus: Set[String]) extends VtepEntry {
    override val uuid = if (id == null) UUID.randomUUID() else id
    val name: String = if (ppName == null) "" else ppName
    val description: String = if (desc == null || desc.isEmpty) null else desc

    val vlanBindings: Map[Integer, UUID] =
        if (bindings == null) Map() else bindings
    val vlanStats: Map[Integer, UUID] =
        if (stats == null) Map() else stats
    val portFaultStatus: Set[String] =
        if (faultStatus == null) Set() else faultStatus

    def newBinding(vni: Integer, lsId: UUID) =
        PhysicalPort(uuid, name, description, vlanBindings updated (vni, lsId),
                     vlanStats, portFaultStatus)
    def clearBinding(vni: Integer) =
        PhysicalPort(uuid, name, description, vlanBindings - vni,
                     vlanStats - vni, portFaultStatus)
    def clearBindings(lsId: UUID) =
        PhysicalPort(uuid, name, description,
                     vlanBindings.filterNot(_._2 == lsId),
                     vlanStats.filterNot(
                         e => vlanBindings.getOrElse(e._1, null) == lsId),
                     portFaultStatus)
    def isBoundToLogicalSwitchId(lsId: UUID): Boolean =
        vlanBindings.exists(_._2 == lsId)

    private val str: String = "PhysicalPort{" +
        "uuid=" + uuid + ", " +
        "name='" + name + "', " +
        "description='" + description + ", " +
        "bindings='" + vlanBindings + "'}"

    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case that: PhysicalPort =>
            Objects.equals(name, that.name) &&
            Objects.equals(description, that.description) &&
            Objects.equals(vlanBindings, that.vlanBindings) &&
            Objects.equals(vlanStats, that.vlanStats) &&
            Objects.equals(portFaultStatus, that.portFaultStatus)
        case other => false
    }
}

object PhysicalPort {
    def apply(id: UUID, name: String, desc: String,
              bindings: Map[Integer, UUID], stats: Map[Integer, UUID],
              faultStatus: Set[String]): PhysicalPort =
        new PhysicalPort(id, name, desc, bindings, stats, faultStatus)

    // Java compatibility stuff
    def apply(id: UUID, name: String, desc: String,
              bindings: util.Map[Integer, UUID], stats: util.Map[Integer, UUID],
              faultStatus: util.Set[String]): PhysicalPort =
        new PhysicalPort(
            id, name, desc,
            if (bindings == null) null else mapAsScalaMap(bindings).toMap,
            if (stats == null) null else mapAsScalaMap(stats).toMap,
            if (faultStatus == null) null else asScalaSet(faultStatus).toSet)

    // For testing purposes
    def apply(id: UUID, name: String, desc: String): PhysicalPort =
        new PhysicalPort(id, name, desc, null, null, null)

}


