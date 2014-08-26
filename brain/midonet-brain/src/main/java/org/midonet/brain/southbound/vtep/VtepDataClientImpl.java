/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Logical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Remote;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Remote;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.brain.southbound.vtep.model.VtepModelTranslator;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * The implementation of the VTEP data client interface, providing methods to
 * read/write VTEP data. The class maintains a stateful connection to the VTEP,
 * allowing multiple users to share the same connection and to recover
 * automatically from a connection failure.
 */
public class VtepDataClientImpl extends VtepDataClientBase {

    private static final Logger log =
        LoggerFactory.getLogger(VtepDataClientImpl.class);

    /**
     * Constructor.
     * @param endPoint The VTEP management end-point.
     * @param configurationService The OVS-DB configuration service.
     * @param connectionService The OVS-DB connection service.
     */
    protected VtepDataClientImpl(VtepEndPoint endPoint,
                                 ConfigurationService configurationService,
                                 ConnectionService connectionService) {
        super(endPoint, configurationService, connectionService);
    }

    /**
     * Lists all physical switches configured in the VTEP.
     */
    @Override
    public @Nonnull List<PhysicalSwitch> listPhysicalSwitches()
        throws VtepNotConnectedException {
        return listPhysicalSwitches(getConnectionNodeOrThrow());
    }

    /**
     * Lists all the physical ports in a given physical switch.
     *
     * TODO replace this implementation with an actual query to the OVSDB,
     * that'll require moving the code to the ConfigurationService probably,
     * similarly as is done for the bind operation.
     *
     * @param psId The physical switch identifier.
     * @return The list of physical ports.
     */
    @Override
    public @Nonnull List<PhysicalPort> listPhysicalPorts(UUID psId)
        throws VtepNotConnectedException {
        Map<String, Table<?>> tableCachePs =
            getTableCacheOrThrow(Physical_Switch.NAME.getName());
        Map<String, Table<?>> tableCachePorts =
            getTableCacheOrThrow(Physical_Port.NAME.getName());
        if (tableCachePs == null || tableCachePorts == null) {
            return new ArrayList<>();
        }

        Physical_Switch ps = null;
        for (Map.Entry<String, Table<?>> e : tableCachePs.entrySet()) {
            if (e.getKey().equals(psId.toString())) {
                ps = (Physical_Switch)e.getValue();
                break;
            }
        }

        if (null == ps) {
            return new ArrayList<>();
        }

        List<PhysicalPort> result = new ArrayList<>();
        for (Map.Entry<String, Table<?>> e : tableCachePorts.entrySet()) {
            log.debug("Found Physical Port {} {}", e.getKey(), e.getValue());
            if (ps.getPorts().contains(new UUID(e.getKey()))) {
                Physical_Port ovsdbPort = (Physical_Port) e.getValue();
                result.add(VtepModelTranslator.toMido(ovsdbPort));
            }
        }
        return result;
    }

    /**
     * Lists all logical switches configured in the VTEP.
     */
    @Override
    public @Nonnull List<LogicalSwitch> listLogicalSwitches()
        throws VtepNotConnectedException {
        Map<String, Table<?>> tableCache =
            getTableCacheOrThrow(Logical_Switch.NAME.getName());
        if (tableCache == null) {
            return new ArrayList<>();
        }
        List<LogicalSwitch> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Logical Switch {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Logical_Switch)e.getValue(),
                                               new UUID(e.getKey())));
        }
        return res;
    }

    /**
     * Lists all the multicast local MACs.
     */
    @Override
    public @Nonnull List<McastMac> listMcastMacsLocal()
        throws VtepNotConnectedException {
        log.debug("Listing multicast local MACs");
        Map<String, Table<?>> tableCache =
            getTableCacheOrThrow(Mcast_Macs_Local.NAME.getName());
        if (tableCache == null) {
            return new ArrayList<>();
        }
        List<McastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found MAC {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Mcast_Macs_Local)e.getValue()));
        }
        return res;
    }

    /**
     * Lists all the multicast remote MACs.
     */
    @Override
    public @Nonnull List<McastMac> listMcastMacsRemote()
        throws VtepNotConnectedException {
        log.debug("Listing multicast remote MACs");
        Map<String, Table<?>> tableCache =
            getTableCacheOrThrow(Mcast_Macs_Remote.NAME.getName());
        if (tableCache == null) {
            return new ArrayList<>();
        }
        List<McastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found MAC {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido(
                (Mcast_Macs_Remote)e.getValue()));
        }
        return res;
    }

    /**
     * Lists all the unicast local MACs.
     */
    @Override
    public @Nonnull List<UcastMac> listUcastMacsLocal()
        throws VtepNotConnectedException {
        log.debug("Listing unicast local MACs");
        Map<String, Table<?>> tableCache =
            getTableCacheOrThrow(Ucast_Macs_Local.NAME.getName());
        if (tableCache == null) {
            return new ArrayList<>();
        }
        List<UcastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found MAC {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Ucast_Macs_Local)e.getValue()));
        }
        return res;
    }

    /**
     * Lists all the unicast remote MACs.
     */
    @Override
    public @Nonnull List<UcastMac> listUcastMacsRemote()
        throws VtepNotConnectedException {

        log.debug("Listing unicast remote MACs");
        Map<String, Table<?>> tableCache =
            getTableCacheOrThrow(Ucast_Macs_Remote.NAME.getName());
        if (tableCache == null) {
            return new ArrayList<>();
        }
        List<UcastMac> result = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found MAC {} {}", e.getKey(), e.getValue());
            result.add(VtepModelTranslator.toMido(
                (Ucast_Macs_Remote)e.getValue()));
        }
        return result;
    }

    /**
     * Gets a logical switch by identifier.
     */
    @Override
    public LogicalSwitch getLogicalSwitch(@Nonnull UUID lsId)
        throws VtepNotConnectedException {

        log.debug("Fetching logical switch {}", lsId);
        Map<String, Table<?>> tableCache =
            getTableCacheOrThrow(Logical_Switch.NAME.getName());
        if (tableCache == null) {
            return null;
        }
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            if (e.getKey().equals(lsId.toString())) {
                log.debug("Found logical switch {} {}", e.getKey(),
                          e.getValue());
                return VtepModelTranslator.toMido(
                    (Logical_Switch)e.getValue(), new UUID(e.getKey()));
            }
        }
        return null;
    }

    /**
     * Gets a logical switch by name.
     */
    @Override
    public LogicalSwitch getLogicalSwitch(@Nonnull String lsName)
        throws VtepNotConnectedException {

        Logical_Switch ls = configurationService.vtepGetLogicalSwitch(
            getConnectionNodeOrThrow(), lsName);

        return ls != null ? VtepModelTranslator.toMido(ls, ls.getId()) : null;
    }

    /**
     * Adds a new logical switch using the given VNI as tunnel key.
     *
     * @param lsName The logical switch name.
     * @param vni The VNI.
     * @return The operation result status.
     */
    @Override
    public StatusWithUuid addLogicalSwitch(@Nonnull String lsName, int vni) {
        Node node = getConnectionNode();
        if (null == node) {
            return new StatusWithUuid(StatusCode.NOSERVICE,
                                      "VTEP not connected");
        }

        log.debug("Adding logical switch {} with VNI {}", lsName, vni);
        StatusWithUuid status =
            configurationService.vtepAddLogicalSwitch(node, lsName, vni);
        if (!status.isSuccess()) {
            log.warn("Adding logical switch failed: {}", status);
        }
        return status;
    }

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
    @Override
    public Status bindVlan(@Nonnull String lsName, @Nonnull String portName,
                           short vlan, int vni,
                           @Nullable Collection<IPv4Addr> floodIps) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Binding VLAN {} on physical port {} to logical switch {} "
                  + "(VNI {}) and adding flooding IPs: {}",
                  lsName, portName, vlan, vni, floodIps);

        List<String> ips;
        if (null != floodIps) {
            ips = new ArrayList<>(floodIps.size());
            for (IPv4Addr ip : floodIps) {
                ips.add(ip.toString());
            }
        } else {
            ips = new ArrayList<>(0);
        }
        Status status = configurationService.vtepBindVlan(
            node, lsName, portName, vlan, vni, ips);
        if (!status.isSuccess()) {
            log.warn("Binding VLAN failed: {}", status);
        }
        return status;
    }

    /**
     * Binds a list of port-VLAN pairs to a given logical switch.
     *
     * @param lsId The logical switch identifier.
     * @param bindings An array of physical port and VLAN bindings .
     *
     * @return The operation result status.
     */
    @Override
    public Status addBindings(
        @Nonnull UUID lsId,
        @Nonnull Collection<Pair<String, Short>> bindings) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Adding bindings to logical switch {} port-VLAN pairs {}",
                  lsId, bindings);
        Status status = configurationService.vtepAddBindings(node, lsId,
                                                             bindings);
        if (!status.isSuccess()) {
            log.warn("Adding bindings failed: {}", status);
        }
        return status;
    }

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
    @Override
    public Status addUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                    @Nullable IPv4Addr macIp,
                                    @Nonnull IPv4Addr tunnelIp) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Adding unicast remote MAC {} with IP {} to logical switch "
                  + "{} on VXLAN endpoint {}", lsName, mac, macIp, tunnelIp);
        StatusWithUuid status = configurationService.vtepAddUcastMacRemote(
                node, lsName, mac.toString(), tunnelIp.toString(),
            macIp == null ? null : macIp.toString());
        if (!status.isSuccess()) {
            log.warn("Adding unicast remote MAC failed: {}", status);
        }
        return status;
    }

    /**
     * Adds a new entry to the Mcast_Macs_Remote table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @param tunnelIp The IP of the VXLAN tunnel peer to where packets
     *                 addressed to MAC should be tunnelled.
     * @return The operation result status.
     */
    @Override
    public Status addMcastMacRemote(@Nonnull String lsName,
                                    @Nonnull VtepMAC mac,
                                    @Nonnull IPv4Addr tunnelIp) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Adding multicast remote MAC {} to logical switch {} on "
                  + "VXLAN endpoint {}", lsName, mac, tunnelIp);
        StatusWithUuid status = configurationService.vtepAddMcastMacRemote(
            node, lsName, mac.toString(), tunnelIp.toString());
        if (!status.isSuccess()) {
            log.warn("Adding multicast remote MAC failed: {}", status);
        }
        return status;
    }

    /**
     * Delete an entry with the specified MAC and MAC IP address from the
     * Ucast_Mac_Remote table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @param macIp The IP address.
     * @return The operation result status.
     */
    @Override
    public Status deleteUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                       @Nonnull IPv4Addr macIp) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Deleting unicast remote MAC {} from logical switch {} with "
                  + "MAC IP {}", mac, lsName, macIp);
        Status status = configurationService.vtepDelUcastMacRemote(
            node, lsName, mac.toString(), macIp.toString());
        if (!status.isSuccess()) {
            log.warn("Deleting unicast remote MAC failed: {}", status);
        }
        return status;
    }

    /**
     * Delete all entries with the specified MAC from the Ucast_Mac_Remote
     * table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @return The operation result status.
     */
    @Override
    public Status deleteAllUcastMacRemote(@Nonnull String lsName,
                                          @Nonnull MAC mac) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Deleting all unicast remote MAC {} from logical switch {}",
                  mac, lsName);
        Status status = configurationService.vtepDelUcastMacRemote(
            node, lsName, mac.toString());
        if (!status.isSuccess()) {
            log.warn("Deleting all unicast remote MAC failed: {}",
                     status);
        }
        return status;
    }

    /**
     * Deletes all entries with the specified MAC from the Mcast_Mac_Remote
     * table.
     *
     * @param lsName The logical switch name.
     * @param mac The MAC address.
     * @return The operation result status.
     */
    @Override
    public Status deleteAllMcastMacRemote(@Nonnull String lsName,
                                          @Nonnull VtepMAC mac) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Deleting all multicast remote MAC {} from logical switch {}",
                  mac, lsName);
        Status status = configurationService.vtepDelMcastMacRemote(
            node, lsName, mac.toString());
        if (!status.isSuccess()) {
            log.warn("Deleting all multicast remote MAC failed: {}", status);
        }
        return status;
    }

    /**
     * Deletes a port-VLAN binding for the current physical switch.
     *
     * @param portName The name of the physical port.
     * @param vlan The VLAN.
     * @return The operation result status.
     */
    @Override
    public Status deleteBinding(@Nonnull String portName, short vlan) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Deleting binding of port {} and VLAN {}", portName, vlan);

        PhysicalSwitch ps = getCurrentPhysicalSwitch();
        if (null == ps) {
            return new Status(StatusCode.NOTFOUND, "Physical switch not found");
        }

        Status status =
            configurationService.vtepDelBinding(node, ps.uuid, portName, vlan);
        if (!status.isSuccess()) {
            log.warn("Delete binding failed: {}", status);
        }
        return status;
    }

    /**
     * Deletes a logical switch, with all the unicast and multicast MAC
     * addresses.
     *
     * @param lsName The logical switch name.
     * @return The operation result status.
     */
    @Override
    public Status deleteLogicalSwitch(@Nonnull String lsName) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        }

        log.debug("Deleting logical switch {}", lsName);
        Status status = configurationService.vtepDelLogicalSwitch(node, lsName);
        if (!status.isSuccess()) {
            log.warn("Deleting logical switch failed: {}", status);
        }
        return status;
    }

    /**
     * Gets the list of port-VLAN bindings for a logical switch.
     *
     * @param lsId The logical switch identifier.
     * @return The collection of port-VLAN bindings.
     * @throws VtepNotConnectedException Exception thrown if the VTEP is not
     * connected.
     */
    @Override
    public List<Pair<UUID, Short>> listPortVlanBindings(UUID lsId)
        throws VtepNotConnectedException {
        return configurationService.vtepPortVlanBindings(
            getConnectionNodeOrThrow(), lsId);
    }

    /**
     * Clears all the bindings for a logical switch.
     *
     * @param lsId The logical switch identifier.
     * @return The operation result status.
     */
    @Override
    public Status clearBindings(UUID lsId) {
        Node node = getConnectionNode();
        if(null == node) {
            return new Status(StatusCode.NOSERVICE, "VTEP not connected");
        } else {
            return configurationService.vtepClearBindings(node, lsId);
        }
    }
}
