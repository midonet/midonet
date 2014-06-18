/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.List;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import rx.Observable;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;

/**
 * Represents a connection to a VTEP-enabled switch.
 */
public interface VtepDataClient {

    /**
     * @return the management ip that this client connects to.
     */
    public IPv4Addr getManagementIp();

    /**
     * @return the management UDP port where this client connects to.
     */
    public int getManagementPort();

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
     * Retrieve a LogicalSwitch by UUID.
     */
    public LogicalSwitch getLogicalSwitch(UUID id);

    /**
     * Retrieve a LogicalSwitch by name.
     */
    public LogicalSwitch getLogicalSwitch(String name);

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
     * @return operation result status
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
     * @return operation result status
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
     * @return the result of the operation
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
     * @return the result of the operation
     */
    public Status addMcastMacRemote(String lsName, String mac, String ip);

    /**
     * Delete an entry in the Ucast_Mac_Remote table.
     *
     * @param mac the MAC address
     * @param lsName the logical switch name
     * @return operation result status
     */
    public Status delUcastMacRemote(String mac, String lsName);

    /**
     * Provides an Observable producing a stream of updates from the Vtep.
     */
    public Observable<TableUpdates> observableUpdates();

    /**
     * Deletes a binding between the given port and vlan.
     *
     * @param portName the of the physical port
     * @param vlanId the vlan id
     * @return the result of the operation
     */
    public Status deleteBinding(String portName, int vlanId);

    /**
     * Deletes the logical switch, with all its bindings and associated
     * ucast / mcast remote macs.
     * @param name of the logical switch
     * @return the result of the operation
     */
    public Status deleteLogicalSwitch(String name);

}
