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
package org.midonet.cluster.data.neutron;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;

import org.apache.zookeeper.Op;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Converter;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpV6ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager.Host;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

public class NetworkZkManager extends BaseZkManager {

    private final BridgeZkManager bridgeZkManager;
    private final BridgeDhcpZkManager dhcpZkManager;
    private final BridgeDhcpV6ZkManager dhcpV6ZkManager;
    private final PortZkManager portZkManager;

    @Inject
    public NetworkZkManager(ZkManager zk,
                            PathBuilder paths,
                            Serializer serializer,
                            BridgeZkManager bridgeZkManager,
                            BridgeDhcpZkManager dhcpZkManager,
                            BridgeDhcpV6ZkManager dhcpV6ZkManager,
                            PortZkManager portZkManager) {
        super(zk, paths, serializer);
        this.bridgeZkManager = bridgeZkManager;
        this.dhcpZkManager = dhcpZkManager;
        this.dhcpV6ZkManager = dhcpV6ZkManager;
        this.portZkManager = portZkManager;
    }

    /**
     * Network methods
     */

    public void prepareCreateNetwork(List<Op> ops, Network network)
        throws SerializationException, StateAccessException {

        String path = paths.getNeutronNetworkPath(network.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(network)));

        BridgeConfig config = new BridgeConfig(network);
        config.setTenantId(network.tenantId);
        ops.addAll(bridgeZkManager.prepareBridgeCreate(network.id, config));
    }

    public void prepareDeleteNetwork(List<Op> ops, UUID id)
        throws SerializationException, StateAccessException {

        String path = paths.getNeutronNetworkPath(id);
        ops.add(zk.getDeleteOp(path));

        if (!bridgeZkManager.exists(id)) {
            return;
        }

        ops.addAll(bridgeZkManager.prepareBridgeDelete(id));

        // Delete Neutron subnets.  That should be the only thing that is still
        // left over after deleting the bridge.
        List<Subnet> subs = getSubnets(id);
        prepareDeleteNeutronSubnets(ops, subs);
    }

    public void prepareUpdateNetwork(List<Op> ops, Network network)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {

        UUID id = network.id;

        BridgeConfig config = new BridgeConfig(network);
        config.setTenantId(network.tenantId);
        ops.addAll(bridgeZkManager.prepareUpdate(id, config));

        String path = paths.getNeutronNetworkPath(id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(network)));
    }

    public Network getNetwork(UUID networkId)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronNetworkPath(networkId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), Network.class);
    }

    public List<Network> getNetworks()
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronNetworksPath();
        Set<UUID> networkIds = getUuidSet(path);

        List<Network> networks = new ArrayList<>();
        for (UUID networkId : networkIds) {
            networks.add(getNetwork(networkId));
        }

        return networks;
    }

    /**
     * Subnet methods
     */

    public void prepareDeleteNeutronSubnets(List<Op> ops, List<Subnet> subnets)
        throws SerializationException, StateAccessException {
        for (Subnet subnet : subnets) {
            String path = paths.getNeutronSubnetPath(subnet.id);
            ops.add(zk.getDeleteOp(path));
        }
        for (Port port : getPorts()) {
            if (port.isInSubnets(subnets)) {
                String portPath = paths.getNeutronPortPath(port.id);
                ops.add(zk.getDeleteOp(portPath));
            }
        }
    }

    public void prepareCreateSubnet(List<Op> ops, Subnet subnet)
        throws SerializationException, StateAccessException {

        if (subnet.isIpv4()) {
            BridgeDhcpZkManager.Subnet config =
                new BridgeDhcpZkManager.Subnet(subnet);
            dhcpZkManager.prepareCreateSubnet(ops, subnet.networkId, config);
        } else if (subnet.isIpv6()) {
            BridgeDhcpV6ZkManager.Subnet6 config =
                new BridgeDhcpV6ZkManager.Subnet6(subnet.ipv6Subnet());
            dhcpV6ZkManager.prepareCreateSubnet6(ops, subnet.networkId, config);
        } else {
            throw new IllegalArgumentException(
                "Subnet version is not recognized: " +
                subnet.getIpVersion());
        }

        String path = paths.getNeutronSubnetPath(subnet.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(subnet)));
    }

    public void prepareDeleteSubnet(List<Op> ops, Subnet subnet)
        throws StateAccessException, SerializationException {
        Preconditions.checkNotNull(subnet);
        if (subnet.isIpv4()) {
            dhcpZkManager.prepareDeleteSubnet(ops, subnet.networkId,
                                              subnet.ipv4Subnet());
        } else if (subnet.isIpv6()) {
            dhcpV6ZkManager.prepareDeleteSubnet6(ops, subnet.networkId,
                                                 subnet.ipv6Subnet());
        } else {
            throw new IllegalArgumentException(
                "Subnet version is not recognized: " +
                subnet.getIpVersion());
        }

        prepareDeleteNeutronSubnets(ops, Collections.singletonList(subnet));
    }

    public void prepareUpdateSubnet(List<Op> ops, Subnet subnet)
        throws SerializationException, StateAccessException {

        if (subnet.isIpv4()) {
            BridgeDhcpZkManager.Subnet config =
                new BridgeDhcpZkManager.Subnet(subnet);
            BridgeDhcpZkManager.Subnet oldConfig =
                dhcpZkManager.getSubnet(subnet.networkId,
                                        config.getSubnetAddr());
            // We need to update the fields that are serialized but are not
            // set when converting from a neutron Subnet (ie. fields that are
            // populated when creating a dhcp port rather than the subnet
            // itself)
            config.setOpt121Routes(oldConfig.getOpt121Routes());
            config.setServerAddr(oldConfig.getServerAddr());
            config.setDefaultGateway((IPv4Addr) subnet.gatewayIpAddr());
            dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, config);
        } else if (subnet.isIpv6()) {
            BridgeDhcpV6ZkManager.Subnet6 config =
                new BridgeDhcpV6ZkManager.Subnet6(subnet.ipv6Subnet());
            dhcpV6ZkManager.prepareUpdateSubnet6(ops, subnet.networkId, config);
        } else {
            throw new IllegalArgumentException(
                "Subnet version is not recognized: " +
                subnet.getIpVersion());
        }

        String path = paths.getNeutronSubnetPath(subnet.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(subnet)));
    }

    public Subnet getSubnet(UUID subnetId)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronSubnetPath(subnetId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), Subnet.class);
    }

    public List<Subnet> getSubnets()
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronSubnetsPath();
        Set<UUID> subnetIds = getUuidSet(path);

        List<Subnet> subnets = new ArrayList<>();
        for (UUID subnetId : subnetIds) {
            subnets.add(getSubnet(subnetId));
        }

        return subnets;
    }

    public List<Subnet> getSubnets(UUID networkId)
        throws StateAccessException, SerializationException {

        List<Subnet> subs = getSubnets();
        List<Subnet> netSubs = new ArrayList<>(subs.size());
        for (Subnet sub : subs) {
            if (Objects.equals(sub.networkId, networkId)) {
                netSubs.add(sub);
            }
        }

        return netSubs;
    }

    public List<IPv4Subnet> getIPv4Subnets(UUID networkId)
        throws SerializationException, StateAccessException {

        List<Subnet> subs = getSubnets(networkId);
        List<IPv4Subnet> ipv4Subnets = new ArrayList<>(subs.size());
        for (Subnet sub : subs) {
            if (sub.isIpv4()) {
                ipv4Subnets.add(sub.ipv4Subnet());
            }
        }

        return ipv4Subnets;
    }

    private void prepareCreateDhcpHostEntries(List<Op> ops, Port port)
        throws SerializationException, StateAccessException {
        for (IPAllocation fixedIp : port.fixedIps) {
            Subnet subnet = getSubnet(fixedIp.subnetId);
            if (subnet.isIpv4()) {
                dhcpZkManager.prepareAddHost(ops, subnet,
                    new Host(port.macAddress, fixedIp.ipAddress,
                        Converter.toExtraDhcpOptsList(port.extraDhcpOpts)));
            } else if (subnet.isIpv6()) {
                dhcpV6ZkManager.prepareAddHost(ops, subnet.networkId,
                                               subnet.ipv6Subnet(),
                                               new BridgeDhcpV6ZkManager.Host(
                                                   port.macAddress,
                                                   fixedIp.ipv6Addr(), null));
            } else {
                throw new IllegalArgumentException(
                    "Subnet version is not recognized: " +
                    subnet.getIpVersion());
            }
        }
    }

    public void prepareCreateNeutronPort(List<Op> ops, Port port)
        throws SerializationException {

        String path = paths.getNeutronPortPath(port.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(port)));
    }

    public BridgePortConfig prepareCreateBridgePort(List<Op> ops, Port port)
        throws SerializationException, StateAccessException {
        BridgePortConfig cfg = new BridgePortConfig(port.networkId,
                                                    port.adminStateUp);
        ops.addAll(portZkManager.prepareCreate(port.id, cfg));
        return cfg;
    }

    public PortConfig prepareCreateVifPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        BridgePortConfig cfg = prepareCreateBridgePort(ops, port);

        String macPortEntryPath = getMacPortEntryPath(port);

        ops.add(zk.getPersistentCreateOp(macPortEntryPath, null));

        // Create DHCP host entries
        prepareCreateDhcpHostEntries(ops, port);

        return cfg;
    }

    public PortConfig prepareCreateDhcpPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        BridgePortConfig cfg = prepareCreateBridgePort(ops, port);

        prepareDhcpIpNeutronData(ops, port.fixedIps);

        return cfg;
    }

    private void prepareDhcpNeutronSubnetUpdate(List<Op> ops, Subnet subnet,
                                                String addr)
        throws StateAccessException, SerializationException {
        BridgeDhcpZkManager.Subnet dhcpSubnet =
            dhcpZkManager
                .getSubnet(subnet.networkId, IPv4Subnet.fromCidr(subnet.cidr));
        dhcpSubnet.addOpt121Route(MetaDataService.IPv4_ADDRESS, addr);
        dhcpSubnet.setServerAddr(IPv4Addr.fromString(addr));
        dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, dhcpSubnet);
    }

    private void prepareDhcpNeutronSubnetRemoveAddr(List<Op> ops, Subnet subnet,
                                                    String addr)
        throws StateAccessException, SerializationException {
        BridgeDhcpZkManager.Subnet dhcpSubnet =
            dhcpZkManager
                .getSubnet(subnet.networkId, IPv4Subnet.fromCidr(subnet.cidr));
        dhcpSubnet.removeOpt121Route(MetaDataService.IPv4_ADDRESS, addr);
        dhcpSubnet.setServerAddr(subnet.gatewayIp == null ? null :
                                 IPv4Addr.fromString(subnet.gatewayIp));
        dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, dhcpSubnet);
    }

    private void prepareDhcpIpNeutronData(List<Op> ops,
                                          List<IPAllocation> fixedIps)
        throws SerializationException, StateAccessException {

        for (IPAllocation fixedIp : fixedIps) {
            Subnet subnet = getSubnet(fixedIp.subnetId);
            // The subnet could be null if it was deleted before the dhcp port
            // is created. This is possible because the dhcp port is attached
            // to the network, not the subnet.
            if (subnet == null) {
                continue;
            }
            if (!subnet.isIpv4()) {
                continue;
            }
            prepareDhcpNeutronSubnetUpdate(ops, subnet, fixedIp.ipAddress);
        }
    }

    public void prepareDeleteNeutronPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronPortPath(port.id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareDeleteDhcpHostEntries(List<Op> ops, Port port)
        throws SerializationException, StateAccessException {

        for (IPAllocation ipAlloc : port.fixedIps) {
            Subnet subnet = getSubnet(ipAlloc.subnetId);
            if (subnet.isIpv4()) {
                dhcpZkManager.prepareDeleteHost(ops, subnet.networkId,
                                                IPv4Subnet
                                                    .fromCidr(subnet.cidr),
                                                port.macAddress);
            } else if (subnet.isIpv6()) {
                dhcpV6ZkManager.prepareDeleteHost(ops, subnet.networkId,
                                                  subnet.ipv6Subnet(),
                                                  port.macAddress);
            } else {
                throw new IllegalArgumentException(
                    "Subnet version is not recognized: " +
                    subnet.getIpVersion());
            }
        }
    }

    /**
     * This is a safe deletion method where deleting a non-existent data does
     * not throw any exception.  This method is useful because there are cases
     * where the port data in Midonet ZK directory is removed but not the data
     * in the Neutron ZK directory.  For example, when a network is deleted, the
     * BridgeZkManager also deletes the ports in the associated bridge,
     * including the DHCP port.  But Neutron would send another request to
     * delete this DHCP port and in that case, even if the port data in MidoNet
     * Zk directory no longer exists, we should still accept it as a valid
     * state.
     */
    public PortConfig prepareDeletePortConfig(List<Op> ops, UUID portId)
        throws SerializationException, StateAccessException {

        PortConfig p = portZkManager.tryGet(portId);
        if (p != null) {
            portZkManager.prepareDelete(ops, p, true);
            return p;
        } else {
            log.warn("Non-existent port deletion attempted: {}", portId);
            return null;
        }
    }

    private String getMacPortEntryPath(Port port) {
        String macEntry = MacPortMap.encodePersistentPath(port.macAddress(), port.id);
        String path = paths.getBridgeMacPortEntryPath(port.networkId,
                                                      Bridge.UNTAGGED_VLAN_ID,
                                                      macEntry);
        return path;
    }

    public void prepareDeletePersistentMac(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {
        String path = getMacPortEntryPath(port);

        // In the case of upgrade, the entry may not exist.
        if (zk.exists(path)) {
            ops.add(zk.getDeleteOp(path));
        }
    }

    public void prepareDeleteVifPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        // Remove DHCP mappings
        prepareDeleteDhcpHostEntries(ops, port);

        prepareDeletePersistentMac(ops, port);

        // Remove the port config
        prepareDeletePortConfig(ops, port.id);
    }

    private void prepareDeleteDhcpMetadataRoutes(List<Op> ops,
                                                 List<IPAllocation> fixedIps)
        throws SerializationException, StateAccessException {

        for (IPAllocation fixedIp : fixedIps) {
            Subnet subnet = getSubnet(fixedIp.subnetId);
            // The subnet could be null if it was deleted before the dhcp port
            // is deleted. This is possible because the dhcp port is attached
            // to the network, not the subnet.
            if (subnet == null) {
                continue;
            }
            if (!subnet.isIpv4()) {
                continue;
            }
            prepareDhcpNeutronSubnetRemoveAddr(ops, subnet, fixedIp.ipAddress);
        }
    }

    public void prepareDeleteDhcpPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        prepareDeletePortConfig(ops, port.id);
        prepareDeleteDhcpMetadataRoutes(ops, port.fixedIps);
    }

    public Port getPort(UUID portId)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronPortPath(portId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), Port.class);
    }

    public Port getDhcpPort(UUID networkId)
        throws SerializationException, StateAccessException {

        // TODO: This is very inefficient.  Fix it by tagging bridge config
        // with the DHCP port
        List<Port> ports = getPorts();
        for (Port port : ports) {
            if (port.isDhcp(networkId)) {
                return port;
            }
        }

        return null;
    }

    public List<Port> getPorts()
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronPortsPath();
        Set<UUID> portIds = getUuidSet(path);

        List<Port> ports = new ArrayList<>();
        for (UUID portId : portIds) {
            ports.add(getPort(portId));
        }

        return ports;
    }

    public List<Port> getPorts(UUID netId)
        throws StateAccessException, SerializationException {

        List<Port> ports = getPorts();
        List<Port> netPorts = new ArrayList<>();
        for (Port port : ports) {
            if (port.networkId.equals(netId)) {
                netPorts.add(port);
            }
        }

        return netPorts;
    }

    public Port findPort(Predicate<Port> predicate)
        throws StateAccessException, SerializationException {
        try {
            return Iterables.find(getPorts(), predicate);
        } catch (NoSuchElementException ex) {
            return null;
        }
    }

    public void prepareUpdateVifPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        // This should throw NoStatePathException
        portZkManager.prepareUpdatePortAdminState(ops, port.id,
                                                  port.adminStateUp);

        // If there are fixed IPs, adjust the DHCP host entries
        if (port.fixedIps != null) {

            // Remove and re-add DHCP host mappings
            Port p = getPort(port.id);
            prepareDeleteDhcpHostEntries(ops, p);
            prepareCreateDhcpHostEntries(ops, port);
        }
    }

    public void prepareUpdateDhcpPort(List<Op> ops, Port port)
        throws StateAccessException, SerializationException {

        // This should throw NoStatePathException
        portZkManager.prepareUpdatePortAdminState(ops, port.id,
                                                  port.adminStateUp);

        // If 'fixed_ips' are specified, they are new IPs to be assigned.
        if (port.fixedIps != null) {

            // Add new metadata DHCP option routes
            prepareDhcpIpNeutronData(ops, port.fixedIps);
        }
    }

    public void prepareUpdateNeutronPort(List<Op> ops, Port port)
        throws SerializationException {
        // Update the neutron port config
        String path = paths.getNeutronPortPath(port.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(port)));
    }
}
