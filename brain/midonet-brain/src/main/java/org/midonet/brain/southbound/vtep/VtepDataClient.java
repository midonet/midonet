/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.List;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import rx.Observable;

/**
 * Represents a connection to a VTEP-enabled switch.
 */
public interface VtepDataClient {

    /**
     * Lists all physical switches configured in the VTEP.
     *
     * @return the physical switches.
     */
    public List<PhysicalSwitch> listPhysicalSwitches();

    /**
     * Lists all logical switches configured in the VTEP.
     *
     * @return the logical switches.
     */
    public List<LogicalSwitch> listLogicalSwitches();

    /**
     * Lists all the physical ports in a given physical switch.
     * @param psUuid uuid of the physical switch
     * @return the list of physical ports
     */
    public List<PhysicalPort> listPhysicalPorts(
        org.opendaylight.ovsdb.lib.notation.UUID psUuid);

    public List<McastMac> listMcastMacsLocal();

    public List<McastMac> listMcastMacsRemote();

    public List<UcastMac> listUcastMacsLocal();

    public List<UcastMac> listUcastMacsRemote();

    /**
     * Connect to the VTEP database instance:
     *
     * @param mgmtIp the management ip of the VTEP
     * @param port the management port of the VTEP
     */
    public void connect(IPv4Addr mgmtIp, int port);

    /**
     * Disconnect from the VTEP database instance:
     */
    public void disconnect();

    /**
     * Adds a new logical switch to the remote VTEP instance, using the
     * given VNI as tunnel key.
     *
     * @param name the name of the new logical switch
     * @param vni the VNI associated to the new logical switch
     * @return the UUID of the new logical switch
     */
    public StatusWithUuid addLogicalSwitch(String name, int vni);

    /**
     * Binds a physical port and vlan to the given logical switch.
     *
     * @param lsName of the logical switch
     * @param portName the physical port in the physical switch
     * @param vlan vlan tag to match for traffic on the given phys. port
     * @param vni vni to use if the logical switch does not exist
     * @param floodIps ips of the vtep peers that will get a remote Mcast and
     *                 Ucast entry for unknown-dst.
     *
     * @return true when the binding was created successfully, false otherwise
     */
    public Status bindVlan(String lsName, String portName, int vlan,
                           Integer vni, List<String> floodIps);

    /**
     * Adds a new entry to the Ucast_Macs_Remote table.
     *
     * @param lsName of the logical switch where mac is to be added
     * @param mac the mac address, must be a valid mac, or
     *            VtepConstants.UNKNOWN-DST
     * @param ip the ip of the vxlan tunnel peer where packets addressed to
     *           mac should be tunnelled to
     * @return true if success, false otherwise
     */
    public Status addUcastMacRemote(String lsName, String mac, String ip);

    /**
     * Adds a new entry to the Mcast_Macs_Remote table.
     *
     * @param lsName of the logical switch where mac is to be added
     * @param mac the mac address, must be a valid mac, or
     *            VtepConstants.UNKNOWN-DST
     * @param ip the ip of the vxlan tunnel peer where packets addressed to
     *           mac should be tunnelled to
     * @return true if success, false otherwise
     */
    public Status addMcastMacRemote(String lsName, String mac, String ip);

    /**
     * Provides an Observable producing a stream of updates from the Vtep of
     * unicast MACs that are local to the vtep.
     */
    public Observable<TableUpdates> observableLocalMacTable();

}
