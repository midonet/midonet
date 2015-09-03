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

import scala.collection.JavaConversions.asScalaSet

import org.midonet.packets.IPv4Addr

/**
 * Represents a VTEP's physical switch info. Note that the lists and sets
 * provided as fields maybe empty but not null.
 * Due to some ovsdb specifications being unclear on using null values or
 * empty strings to indicate unset string values, this class unifies both
 * representations. Note that 'name', as an identifier, is not allowed to
 * be null.
 * @param id is UUID of this switch in OVSDB
 * @param psName is the name of the switch
 * @param desc is the description associated to this switch
 * @param pList is the list of physical port names in the VTEP
 * @param mgmtList is the list of management ips for this physical switch
 * @param tunnelList is set of tunnel ips (may be empty)
 */
final class PhysicalSwitch(id: UUID, psName: String, desc: String,
                           pList: Set[UUID],
                           mgmtList: Set[IPv4Addr],
                           tunnelList: Set[IPv4Addr]) extends VtepEntry {
    override val uuid = if (id == null) UUID.randomUUID() else id
    val name: String = if (psName == null) "" else psName
    val description: String = if (desc == null || desc.isEmpty) null else desc
    val ports: Set[UUID] = if (pList == null) Set() else pList
    val mgmtIps: Set[IPv4Addr] = if (mgmtList == null) Set() else mgmtList
    val tunnelIps: Set[IPv4Addr] = if (tunnelList == null) Set() else tunnelList

    /** Convenience method to interface with the low-level ovsdb representation
      * of ip lists as strings */
    def mgmtIpStrings: Iterable[String] = mgmtIps.map(_.toString)

    /** Convenience method to interface with the low-level ovsdb representation
      * of ip lists as strings */
    def tunnelIpStrings: Iterable[String] = tunnelIps.map(_.toString)

    // Actually, ovsdb uses an empty set to indicate that the tunnelIp is not
    // set... If not empty, there should be only one ip in the set (though
    // the ovsdb specs theoretically allow for several)
    val tunnelIp: Option[IPv4Addr] = tunnelIps.headOption

    val str: String = "PhysicalSwitch{" +
                          "uuid=" + uuid + ", " +
                          "name='" + name + "', " +
                          "description='" + description + "', " +
                          "ports=" + ports + ", " +
                          "mgmtIps=" + mgmtIps + ", " +
                          "tunnelIps=" + tunnelIps + "}"

    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case that: PhysicalSwitch =>
            Objects.equals(name, that.name) &&
            Objects.equals(description, that.description) &&
            Objects.equals(ports, that.ports) &&
            Objects.equals(mgmtIps, that.mgmtIps) &&
            Objects.equals(tunnelIps, that.tunnelIps)
        case other => false
    }
}

object PhysicalSwitch {

    def apply(id: UUID, name: String, desc: String, p: Set[UUID],
              mgmt: Set[IPv4Addr], tunnel: Set[IPv4Addr]) =
        new PhysicalSwitch(id, name, desc, p, mgmt, tunnel)

    // Java compatibility stuff
    def apply(id: UUID, name: String, desc: String, p: util.Set[UUID],
              mgmt: util.Set[IPv4Addr], tunnel: util.Set[IPv4Addr]) =
        new PhysicalSwitch(
            id, name, desc,
            if (p == null) null else asScalaSet(p).toSet,
            if (mgmt == null) null else asScalaSet(mgmt).toSet,
            if (tunnel == null) null else asScalaSet(tunnel).toSet)
}
