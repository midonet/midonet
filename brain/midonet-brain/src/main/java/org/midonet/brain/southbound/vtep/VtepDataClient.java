/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;

import rx.Observable;
import rx.Subscription;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback;

/**
 * A client class for the connection to a VTEP-enabled switch. A client
 * instance allows multiple users to share the same connection to a VTEP,
 * while monitoring the connection for possible failure and including a
 * recovery mechanism.
 */
public interface VtepDataClient {

    @Immutable
    public enum State {
        CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED, BROKEN, DISPOSED
    }

    /**
     * @return The VTEP management IP for the current client.
     */
    public IPv4Addr getManagementIp();

    /**
     * @return The VTEP tunnel IP.
     */
    public IPv4Addr getTunnelIp();

    /**
     * @return The VTEP management UDP port for the current client.
     */
    public int getManagementPort();

    /**
     * Lists all physical switches configured in the VTEP.
     */
    public @Nonnull List<PhysicalSwitch> listPhysicalSwitches()
        throws VtepNotConnectedException;

    /**
     * Lists all logical switches configured in the VTEP.
     */
    public @Nonnull List<LogicalSwitch> listLogicalSwitches()
        throws VtepNotConnectedException;

    /**
     * Lists all the physical ports in a given physical switch.
     *
     * @param psId The physical switch identifier.
     * @return The list of physical ports.
     */
    public @Nonnull List<PhysicalPort> listPhysicalPorts(
        org.opendaylight.ovsdb.lib.notation.UUID psId)
        throws VtepNotConnectedException;

    /**
     * Lists all the multicast local MACs.
     */
    @SuppressWarnings("unused")
    public @Nonnull List<McastMac> listMcastMacsLocal()
        throws VtepNotConnectedException;

    /**
     * Lists all the multicast remote MACs.
     */
    @SuppressWarnings("unused")
    public @Nonnull List<McastMac> listMcastMacsRemote()
        throws VtepNotConnectedException;

    /**
     * Lists all the unicast local MACs.
     */
    @SuppressWarnings("unused")
    public @Nonnull List<UcastMac> listUcastMacsLocal()
        throws VtepNotConnectedException;

    /**
     * Lists all the unicast remote MACs.
     */
    @SuppressWarnings("unused")
    public @Nonnull List<UcastMac> listUcastMacsRemote()
        throws VtepNotConnectedException;

    /**
     * Gets a logical switch by identifier.
     */
    public @Nullable LogicalSwitch getLogicalSwitch(@Nonnull UUID lsId)
        throws VtepNotConnectedException;

    /**
     * Gets a logical switch by name.
     */
    public @Nullable LogicalSwitch getLogicalSwitch(@Nonnull String lsName)
        throws VtepNotConnectedException;

    /**
     * Adds a new logical switch using the given VNI as tunnel key.
     *
     * @param lsName The logical switch name.
     * @param vni The VNI.
     * @return The operation result status.
     */
    public StatusWithUuid addLogicalSwitch(@Nonnull String lsName, int vni);

    /**
     * Binds a physical port and VLAN to a given logical switch. If the logical
     * switch does not exist, the method creates a new logical switch with the
     * specified VNI.
     *
     * @param lsName The logical switch name.
     * @param portName The name of the physical port.
     * @param vlan The VLAN.
     * @param vni The VNI to use if the logical switch does not exist.
     * @param floodIps The list of IP addresses for the VTEP peers that will be
     *                 added as entries to the remote unicast and multicast
     *                 tables as unknown-dst.
     *
     * @return The operation result status.
     */
    public Status bindVlan(@Nonnull String lsName, @Nonnull String portName,
                           short vlan, int vni,
                           @Nullable Collection<IPv4Addr> floodIps);

    /**
     * Binds a list of port-VLAN pairs to a given logical switch.
     *
     * @param lsId The logical switch identifier.
     * @param bindings An array of physical port and VLAN bindings .
     *
     * @return The operation result status.
     */
    public Status addBindings(
        @Nonnull UUID lsId, @Nonnull Collection<Pair<String, Short>> bindings);

    /**
     * Adds a new entry to the Ucast_Macs_Remote table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @param macIp The IP associated to the MAC address using ARP.
     * @param tunnelIp The IP of the VXLAN tunnel peer to where packets
     *                 addressed to MAC should be tunnelled.
     * @return The operation result status.
     */
    public Status addUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                    @Nullable IPv4Addr macIp,
                                    @Nonnull IPv4Addr tunnelIp);

    /**
     * Adds a new entry to the Mcast_Macs_Remote table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @param tunnelIp The IP of the VXLAN tunnel peer to where packets
     *                 addressed to MAC should be tunnelled.
     * @return The operation result status.
     */
    public Status addMcastMacRemote(@Nonnull String lsName,
                                    @Nonnull VtepMAC mac,
                                    @Nonnull IPv4Addr tunnelIp);

    /**
     * Deletes an entry with the specified MAC and MAC IP address from the
     * Ucast_Mac_Remote table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @param macIp The IP address.
     * @return The operation result status.
     */
    public Status deleteUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                       @Nonnull IPv4Addr macIp);

    /**
     * Deletes all entries with the specified MAC from the Ucast_Mac_Remote
     * table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @return The operation result status.
     */
    public Status deleteAllUcastMacRemote(@Nonnull String lsName,
                                          @Nonnull MAC mac);

    /**
     * Deletes all entries with the specified MAC from the Mcast_Mac_Remote
     * table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @return The operation result status.
     */
    public Status deleteAllMcastMacRemote(@Nonnull String lsName,
                                          @Nonnull VtepMAC mac);

    /**
     * Deletes a port-VLAN binding for the current physical switch.
     *
     * @param portName The name of the physical port.
     * @param vlan The VLAN.
     * @return The operation result status.
     */
    public Status deleteBinding(@Nonnull String portName, short vlan);

    /**
     * Deletes a logical switch, with all the unicast and multicast MAC
     * addresses.
     *
     * @param lsName The logical switch name.
     * @return The operation result status.
     */
    public Status deleteLogicalSwitch(@Nonnull String lsName);

    /**
     * Gets the list of port-VLAN bindings for a logical switch.
     *
     * @param lsId The logical switch identifier.
     * @return The list of port-VLAN bindings.
     * @throws VtepNotConnectedException Exception thrown if the VTEP is not
     * connected.
     */
    @SuppressWarnings("unused")
    public List<Pair<UUID, Short>> listPortVlanBindings(UUID lsId)
        throws VtepNotConnectedException;

    /**
     * Clears all the bindings for a logical switch.
     *
     * @param lsId The logical switch identifier.
     * @return The operation result status.
     */
    public Status clearBindings(UUID lsId);

    /**
     * Disconnects from the VTEP. If the VTEP is already disconnecting or
     * disconnected, the method does nothing. If the connection state is
     * connecting, the method throws an exception.
     *
     * The method does not disconnect if the specified user did not previously
     * call the connect method, or if there are other users still using
     * the connection.
     *
     * @param user The user for this connection.
     * @param lazyDisconnect If true, the methods does dispose the VTEP
     *                       client immediately, event if there are no more
     *                       users for this connection.
     * @throws VtepStateException The client could not disconnect because of
     * an invalid service state after a number of retries.
     */
    @SuppressWarnings("unused")
    public void disconnect(java.util.UUID user, boolean lazyDisconnect) throws
        VtepStateException;

    /**
     * Waits for the VTEP data client to be connected to the VTEP.
     *
     * @throws VtepStateException The client reached a state from which it
     * can no longer become connected.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitConnected() throws VtepStateException;

    /**
     * Waits for the VTEP data client to be connected to the VTEP for the
     * specified timeout interval.
     *
     * @param timeout The timeout interval.
     * @param unit The interval unit.
     * @throws VtepStateException The client reached a state from which it
     * can no longer become connected.
     * @throws TimeoutException The timeout interval expired.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitConnected(long timeout, TimeUnit unit)
        throws VtepStateException, TimeoutException;

    /**
     * Waits for the VTEP data client to be disconnected from the VTEP.
     *
     * @throws VtepStateException The client reached a state from which it
     * can no longer become discconnected.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitDisconnected() throws VtepStateException;

    /**
     * Waits for the VTEP data client to be disconnected from the VTEP for the
     * specified timeout interval.
     *
     * @param timeout The timeout interval.
     * @param unit The interval unit.
     * @throws VtepStateException The client reached a state from which it
     * can no longer become discconnected.
     * @throws TimeoutException The timeout interval expired.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitDisconnected(long timeout, TimeUnit unit)
        throws VtepStateException, TimeoutException;

    /**
     * Waits for the VTEP data client to reach the specified state. The method
     * throws an exception if the specified state can no longer be reached.
     * @param state The state.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitState(State state) throws VtepStateException;

    /**
     * Waits for the VTEP data client to reach the specified state an amount of
     * time.
     * @param state The state.
     * @param timeout The timeout interval.
     * @param unit The interval unit.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitState(State state, long timeout, TimeUnit unit)
        throws TimeoutException;


    /**
     * Gets the current client state.
     */
    public State getState();

    /**
     * Specifies a callback method to execute when the VTEP becomes connected.
     *
     * @param callback A callback instance.
     */
    @SuppressWarnings("unused")
    public Subscription onConnected(
        @Nonnull Callback<VtepDataClient, VtepException> callback);

    /**
     * Provides an observable notifying of the changes to the connection state.
     */
    @SuppressWarnings("unused")
    public Observable<State> stateObservable();

    /**
     * Provides an observable producing a stream of updates from the VTEP.
     */
    public Observable<TableUpdates> updatesObservable();

}
