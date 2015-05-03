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
 * Represents a VTEP's logical switch. Note that the tunnelKey may be null.
 * Due to some ovsdb specifications being unclear on using null values or
 * empty strings to indicate unset string values, this class unifies both
 * representations. Note that 'name', as an identifier, is not allowed to
 * be null.
 *
 * @param id is UUID of this switch in OVSDB (autogenerated if null)
 * @param lsName is the name of the switch
 * @param tunnelKey is the VNI (null if not set)
 * @param lsDesc is the description associated to this switch
 */
final class LogicalSwitch(id: UUID, lsName: String,
                          val tunnelKey: Integer, lsDesc: String)
    extends VtepEntry {
    override val uuid = if (id == null) UUID.randomUUID() else id
    val name = if (lsName == null) "" else lsName
    val description = if (lsDesc == null || lsDesc.isEmpty) null else lsDesc

    /** Corresponding MidoNet NetworkId, assuming the logical switch has
      * a MidoNet-compliant name (mn-<network-id>).
      */
    def networkId: UUID = LogicalSwitch.nameToNetworkId(name)

    private val str: String = s"LogicalSwitch{uuid=$uuid, name=$name, "+
                              s"tunnelKey=$tunnelKey, " +
                              s"description=$description}"
    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case ls: LogicalSwitch =>
            Objects.equals(name, ls.name) &&
            Objects.equals(description, ls.description) &&
            Objects.equals(tunnelKey, ls.tunnelKey)
        case other => false
    }
}

object LogicalSwitch {

    def apply(id: UUID, name: String, vni: Integer, desc: String) =
        new LogicalSwitch(id, name, vni, desc)
    def apply(name: String, vni: Integer, desc: String) =
        new LogicalSwitch(null, name, vni, desc)

    /** prefix prepended to the network uuid when forming
      * a logical switch name */
    private val LogicalSwitchPrefix = "mn-"

    /** Logical switches created to bind a VTEP port and a Midonet network have
      * a name formed as a function of the network uuid. This method extracts
      * the uuid from the logical switch name.  If the logical switch is not
      * created by MidoNet (and the name is not following our conventions) it
      * will return null. */
    def nameToNetworkId(lsName: String): UUID = {
        if (lsName.startsWith(LogicalSwitchPrefix))
            UUID.fromString(lsName.substring(LogicalSwitchPrefix.length))
        else
            null
    }

    /** This method returns the name that should be assigned to a logical switch
      * used to bind a VTEP port with a Midonet bridge */
    def networkIdToLogicalSwitchName(id: UUID): String = LogicalSwitchPrefix + id
}
