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

import scala.collection.JavaConversions._

import org.midonet.packets.IPv4Addr

/**
 * Represents a VTEP's physical switch info.
 * @param uuid is UUID of this switch in OVSDB
 * @param psName is the name of the switch
 * @param desc is the description associated to this switch
 * @param pList is the list of physical port names in the VTEP
 * @param mgmtList is the list of management ips for this physical switch
 * @param tunnelList is set of tunnel ips (may be empty)
 */
final class PhysicalSwitch(val uuid: UUID, psName: String, desc: String,
                           pList: util.Collection[String],
                           mgmtList: util.Set[IPv4Addr],
                           tunnelList: util.Set[IPv4Addr]) {
    val name: String = if (psName == null) "" else psName
    val description: String = if (desc == null || desc.isEmpty) null else desc
    val ports: util.Collection[String] =
        if (pList == null) new util.ArrayList[String]() else pList
    val mgmtIps: util.Set[IPv4Addr] =
        if (mgmtList == null) new util.HashSet[IPv4Addr]() else mgmtList
    val tunnelIps: util.Set[IPv4Addr] =
        if (tunnelList == null) new util.HashSet[IPv4Addr]() else tunnelList

    def mgmtIpStrings: util.Collection[String] =
        mgmtIps.toSet[IPv4Addr].map(_.toString)
    def tunnelIpStrings: util.Collection[String] =
        tunnelIps.toSet[IPv4Addr].map(_.toString)

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
            Objects.equals(name, that.name) &&
            Objects.equals(description, that.description) &&
            Objects.equals(ports, that.ports) &&
            Objects.equals(mgmtIps, that.mgmtIps) &&
            Objects.equals(tunnelIps, that.tunnelIps)
        case other => false
    }

    override def hashCode: Int =
        if (uuid == null) Objects.hash(name, mgmtIps) else uuid.hashCode
}

object PhysicalSwitch {
    def apply(id: UUID, name: String, desc: String, p: util.Collection[String],
                 mgmt: util.Set[IPv4Addr], tunnel: util.Set[IPv4Addr]) =
        new PhysicalSwitch(id, name, desc, p, mgmt, tunnel)
}
