/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.brain.southbound.vtep.model;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Logical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Remote;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Remote;

/**
 * Collection of translators between VTEP entities between the OVSDB schema and
 * the Midonet schema.
 */
public class VtepModelTranslator {

    public static LogicalSwitch toMido(Logical_Switch ovsdbLs) {
        return new LogicalSwitch(
            ovsdbLs.getDescription(),
            ovsdbLs.getName(),
            // NOTE: this complicated thing is necessary because for some
            // reason the tunnel_key OvsdbSet<Integer> contains an array of
            // BigInteger which breaks when trying to use an interator.
            ovsdbLs.getTunnel_key().isEmpty()
                ? null
                :  ((BigInteger)ovsdbLs.getTunnel_key()
                                       .toArray()[0]).intValue()
        );
    }

    public static PhysicalSwitch toMido(Physical_Switch ovsdbPs) {

        Set<String> ports = new HashSet<>();
        for (UUID port : ovsdbPs.getPorts()) {
            ports.add(port.toString());
        }

        return new PhysicalSwitch(
            ovsdbPs.getDescription(),
            ovsdbPs.getName(),
            ports,
            ovsdbPs.getManagement_ips().delegate(),
            ovsdbPs.getTunnel_ips().delegate()
        );
    }

    public static PhysicalPort toMido(Physical_Port ovsdbPort) {
        return new PhysicalPort(
            ovsdbPort.getDescription(),
            ovsdbPort.getName(),
            ovsdbPort.getVlan_bindings(),
            ovsdbPort.getVlan_stats(),
            ovsdbPort.getPort_fault_status()
        );
    }

    public static UcastMac toMido(Ucast_Macs_Remote ovsdbMac) {
        return new UcastMac(
            ovsdbMac.getMac(),
            ovsdbMac.getLogical_switch().isEmpty() ? null : ovsdbMac.getLogical_switch().iterator().next(),
            ovsdbMac.getLocator().isEmpty() ? null : ovsdbMac.getLocator().iterator().next(),
            ovsdbMac.getIpaddr()
        );
    }

    public static UcastMac toMido(Ucast_Macs_Local ovsdbMac) {
        return new UcastMac(
            ovsdbMac.getMac(),
            ovsdbMac.getLogical_switch().isEmpty() ? null : ovsdbMac.getLogical_switch().iterator().next(),
            ovsdbMac.getLocator().isEmpty() ? null : ovsdbMac.getLocator().iterator().next(),
            ovsdbMac.getIpaddr()
        );
    }

    public static McastMac toMido(Mcast_Macs_Local ovsdbMac) {
        return new McastMac(
            ovsdbMac.getMac(),
            ovsdbMac.getLogical_switch().isEmpty() ? null : ovsdbMac.getLogical_switch().iterator().next(),
            ovsdbMac.getLocator_set().isEmpty() ? null : ovsdbMac.getLocator_set().iterator().next(),
            ovsdbMac.getIpaddr()
        );
    }

    public static McastMac toMido(Mcast_Macs_Remote ovsdbMac) {
        return new McastMac(
            ovsdbMac.getMac(),
            ovsdbMac.getLogical_switch().isEmpty() ? null : ovsdbMac.getLogical_switch().iterator().next(),
            ovsdbMac.getLocator_set().isEmpty() ? null : ovsdbMac.getLocator_set().iterator().next(),
            ovsdbMac.getIpaddr()
        );
    }

}

