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
package org.midonet.brain.southbound.vtep.model;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.OvsDBMap;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Logical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Remote;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Remote;

import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.McastMac;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.data.vtep.model.VtepMAC;
import org.midonet.packets.IPv4Addr;

/**
 * Collection of translators between VTEP entities between the OVSDB schema and
 * the Midonet schema.
 */
public class VtepModelTranslator {

    public static java.util.UUID toMido(UUID uuid) {
        return java.util.UUID.fromString(uuid.toString());
    }

    // TODO: ovsdb-specific UUIDs should be eventually erradicated
    public static UUID fromMido(java.util.UUID uuid) {
        return new UUID(uuid.toString());
    }

    public static LogicalSwitch toMido(Logical_Switch ovsdbLs,
                                       java.util.UUID uuid) {
        return new LogicalSwitch(
            uuid,
            ovsdbLs.getName(),
            // NOTE: this complicated thing is necessary because for some
            // reason the tunnel_key OvsdbSet<Integer> contains an array of
            // BigInteger which breaks when trying to use an iterator.
            ovsdbLs.getTunnel_key().isEmpty()
                ? null
                :  ((BigInteger)ovsdbLs.getTunnel_key()
                                       .toArray()[0]).intValue(),
            ovsdbLs.getDescription()
        );
    }

    public static PhysicalSwitch toMido(Physical_Switch ovsdbPs,
                                        java.util.UUID uuid) {

        Set<String> ports = new HashSet<>();
        for (UUID port : ovsdbPs.getPorts()) {
            ports.add(port.toString());
        }
        Set<IPv4Addr> mgmtIps = new HashSet<>();
        for (String ip: ovsdbPs.getManagement_ips().delegate()) {
            mgmtIps.add(IPv4Addr.fromString(ip));
        }
        Set<IPv4Addr> tunnelIps = new HashSet<>();
        for (String ip: ovsdbPs.getTunnel_ips().delegate()) {
            tunnelIps.add(IPv4Addr.fromString(ip));
        }

        return new PhysicalSwitch(
            uuid,
            ovsdbPs.getName(),
            ovsdbPs.getDescription(),
            ports,
            mgmtIps,
            tunnelIps
        );
    }

    public static PhysicalPort toMido(Physical_Port ovsdbPort) {
        return new PhysicalPort(
            ovsdbPort.getDescription(),
            ovsdbPort.getName(),
            bigIntToShort(ovsdbPort.getVlan_bindings()),
            bigIntToShort(ovsdbPort.getVlan_stats()),
            ovsdbPort.getPort_fault_status()
        );
    }

    public static UcastMac toMido(Ucast_Macs_Remote ovsdbMac) {
        return new UcastMac(
            ovsdbMac.getMac(),
            ovsdbMac.getLogical_switch().isEmpty()
                ? null : ovsdbMac.getLogical_switch().iterator().next(),
            ovsdbMac.getLocator().isEmpty()
                ? null : ovsdbMac.getLocator().iterator().next(),
            ovsdbMac.getIpaddr()
        );
    }

    public static UcastMac toMido(Ucast_Macs_Local ovsdbMac) {
        return new UcastMac(
            ovsdbMac.getMac(),
            ovsdbMac.getLogical_switch().isEmpty()
                ? null : ovsdbMac.getLogical_switch().iterator().next(),
            ovsdbMac.getLocator().isEmpty()
                ? null : ovsdbMac.getLocator().iterator().next(),
            ovsdbMac.getIpaddr()
        );
    }

    public static McastMac toMido(Mcast_Macs_Local ovsdbMac) {
        return new McastMac(
            ovsdbMac.getLogical_switch().isEmpty()
                ? null : toMido(ovsdbMac.getLogical_switch().iterator().next()),
            ovsdbMac.getMac() == null? null: VtepMAC.fromString(ovsdbMac.getMac()),
            ovsdbMac.getIpaddr() == null? null: IPv4Addr.fromString(ovsdbMac.getIpaddr()),
            ovsdbMac.getLocator_set().isEmpty()
                ? null : toMido(ovsdbMac.getLocator_set().iterator().next())
        );
    }

    public static McastMac toMido(Mcast_Macs_Remote ovsdbMac) {
        return new McastMac(
            ovsdbMac.getLogical_switch().isEmpty()
                ? null : toMido(ovsdbMac.getLogical_switch().iterator().next()),
            ovsdbMac.getMac() == null? null: VtepMAC.fromString(ovsdbMac.getMac()),
            ovsdbMac.getIpaddr() == null? null: IPv4Addr.fromString(ovsdbMac.getIpaddr()),
            ovsdbMac.getLocator_set().isEmpty()
                ? null : toMido(ovsdbMac.getLocator_set().iterator().next())
        );
    }

    private static <T> OvsDBMap<Short, T>
        bigIntToShort(OvsDBMap<BigInteger, T> map) {
        OvsDBMap<Short, T> shortMap = new OvsDBMap<>();
        for(Map.Entry<BigInteger, T> e : map.entrySet()) {
            shortMap.put(e.getKey().shortValue(), e.getValue());
        }
        return shortMap;
    }

}

