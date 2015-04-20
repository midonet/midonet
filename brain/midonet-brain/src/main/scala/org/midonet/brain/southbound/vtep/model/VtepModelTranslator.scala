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
package org.midonet.brain.southbound.vtep.model

import java.math.BigInteger
import java.util.UUID

import scala.collection.JavaConversions._

import org.opendaylight.ovsdb.lib.notation.OvsDBMap
import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}
import org.opendaylight.ovsdb.lib.table.vtep.Logical_Switch
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Local
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Remote
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Remote

import org.midonet.cluster.data.vtep.model.LogicalSwitch
import org.midonet.cluster.data.vtep.model.McastMac
import org.midonet.cluster.data.vtep.model.PhysicalPort
import org.midonet.cluster.data.vtep.model.PhysicalSwitch
import org.midonet.cluster.data.vtep.model.UcastMac
import org.midonet.packets.IPv4Addr

/**
 * Collection of translators between VTEP entities between the OVSDB schema and
 * the Midonet schema.
 */
object VtepModelTranslator {

    // TODO: ovsdb-specific UUIDs should be eventually erradicated

    def toMido(uuid: OvsdbUUID): UUID = UUID.fromString(uuid.toString)
    def fromMido(uuid: UUID): OvsdbUUID = new OvsdbUUID(uuid.toString)

    def toMido(ovsdbLs: Logical_Switch, uuid: UUID): LogicalSwitch =
        new LogicalSwitch(
            uuid,
            ovsdbLs.getName,
            // NOTE: this complicated thing is necessary because for some
            // reason the tunnel_key OvsdbSet<Integer> contains an array of
            // BigInteger which breaks when trying to use an iterator.
            if (ovsdbLs.getTunnel_key.isEmpty) null else
                ovsdbLs.getTunnel_key.toArray()(0)
                    .asInstanceOf[BigInteger].intValue(),
            ovsdbLs.getDescription
        )

    def toMido(ovsdbPs: Physical_Switch, uuid: UUID): PhysicalSwitch = {
        val ports = ovsdbPs.getPorts.delegate().toSet[OvsdbUUID].map(toMido)
        val mgmntIps = ovsdbPs.getManagement_ips.delegate().toSet
            .map(IPv4Addr.fromString)
        val tunnelIPs = ovsdbPs.getTunnel_ips.delegate().toSet
            .map(IPv4Addr.fromString)
        PhysicalSwitch(uuid, ovsdbPs.getName, ovsdbPs.getDescription,
                       ports, mgmntIps, tunnelIPs)
    }

    def toMido(ovsdbPort: Physical_Port): PhysicalPort =
        PhysicalPort(null, ovsdbPort.getName, ovsdbPort.getDescription,
                     toMido(ovsdbPort.getVlan_bindings),
                     toMido(ovsdbPort.getVlan_stats),
                     if (ovsdbPort.getPort_fault_status == null) null
                     else ovsdbPort.getPort_fault_status.delegate().toSet)

    def toMido(ovsdbMac: Ucast_Macs_Remote): UcastMac =
        UcastMac(
            if (ovsdbMac.getLogical_switch.isEmpty) null
            else toMido(ovsdbMac.getLogical_switch.iterator().next()),
            ovsdbMac.getMac,
            ovsdbMac.getIpaddr,
            if (ovsdbMac.getLocator.isEmpty) null
                else toMido(ovsdbMac.getLocator.iterator().next())
        )

    def toMido(ovsdbMac: Ucast_Macs_Local): UcastMac =
        UcastMac(
            if (ovsdbMac.getLogical_switch.isEmpty) null
            else toMido(ovsdbMac.getLogical_switch.iterator().next()),
            ovsdbMac.getMac,
            ovsdbMac.getIpaddr,
            if (ovsdbMac.getLocator.isEmpty) null
            else toMido(ovsdbMac.getLocator.iterator().next())
        )

    def toMido(ovsdbMac: Mcast_Macs_Remote): McastMac =
        McastMac(
            if (ovsdbMac.getLogical_switch.isEmpty) null
            else toMido(ovsdbMac.getLogical_switch.iterator().next()),
            ovsdbMac.getMac,
            ovsdbMac.getIpaddr,
            if (ovsdbMac.getLocator_set.isEmpty) null
            else toMido(ovsdbMac.getLocator_set.iterator().next())
        )

    def toMido(ovsdbMac: Mcast_Macs_Local): McastMac =
        McastMac(
            if (ovsdbMac.getLogical_switch.isEmpty) null
            else toMido(ovsdbMac.getLogical_switch.iterator().next()),
            ovsdbMac.getMac,
            ovsdbMac.getIpaddr,
            if (ovsdbMac.getLocator_set.isEmpty) null
            else toMido(ovsdbMac.getLocator_set.iterator().next())
        )

    def toMido(map: OvsDBMap[BigInteger, OvsdbUUID]): Map[Integer, UUID] =
        mapAsScalaMap(map)
            .map(e => new Integer(e._1.intValue()) -> toMido(e._2)).toMap
}

