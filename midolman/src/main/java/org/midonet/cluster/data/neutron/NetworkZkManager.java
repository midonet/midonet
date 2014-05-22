/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import java.util.*;
import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager.Opt121;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager.Host;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IntIPv4;

public class NetworkZkManager extends BaseZkManager {

    private final BridgeZkManager bridgeZkManager;
    private final BridgeDhcpZkManager dhcpZkManager;
    private final PortZkManager portZkManager;

    @Inject
    public NetworkZkManager(ZkManager zk,
                            PathBuilder paths,
                            Serializer serializer,
                            BridgeZkManager bridgeZkManager,
                            BridgeDhcpZkManager dhcpZkManager,
                            PortZkManager portZkManager) {
        super(zk, paths, serializer);
        this.bridgeZkManager = bridgeZkManager;
        this.dhcpZkManager = dhcpZkManager;
        this.portZkManager = portZkManager;
    }

    /** Network methods **/

    public void prepareCreateNetwork(List<Op> ops, Network network)
            throws SerializationException, StateAccessException {

        String path = paths.getNeutronNetworkPath(network.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(network)));

        BridgeConfig config = new BridgeConfig(network);
        ops.addAll(bridgeZkManager.prepareBridgeCreate(network.id, config));
    }

    public Network prepareDeleteNetwork(List<Op> ops, UUID id)
            throws SerializationException, StateAccessException {

        Network network = getNetwork(id);
        if (network == null) {
            return null;
        }

        ops.addAll(bridgeZkManager.prepareBridgeDelete(id));

        // Delete Neutron subnets.  That should be the only thing that is still
        // left over after deleting the bridge.
        List<Subnet> subs = getSubnets(id);
        for (Subnet sub : subs) {
            String subPath = paths.getNeutronSubnetPath(sub.id);
            ops.add(zk.getDeleteOp(subPath));
        }

        String path = paths.getNeutronNetworkPath(id);
        ops.add(zk.getDeleteOp(path));

        return network;
    }

    public void prepareUpdateNetwork(List<Op> ops, Network network)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {

        UUID id = network.id;

        BridgeConfig config = new BridgeConfig(network);
        ops.addAll(bridgeZkManager.prepareUpdate(id, config, true));

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

        String path= paths.getNeutronNetworksPath();
        Set<UUID> networkIds = getUuidSet(path);

        List<Network> networks = new ArrayList<>();
        for (UUID networkId : networkIds) {
            networks.add(getNetwork(networkId));
        }

        return networks;
    }

    /** Subnet methods **/

    public void prepareCreateSubnet(List<Op> ops, Subnet subnet)
            throws SerializationException, StateAccessException {

        BridgeDhcpZkManager.Subnet config =
                new BridgeDhcpZkManager.Subnet(subnet);
        dhcpZkManager.prepareCreateSubnet(ops, subnet.networkId, config);

        String path = paths.getNeutronSubnetPath(subnet.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(subnet)));
    }

    public Subnet prepareDeleteSubnet(List<Op> ops, UUID id)
            throws StateAccessException, SerializationException {

        Subnet subnet = getSubnet(id);
        if (subnet == null) {
            return null;
        }

        dhcpZkManager.prepareDeleteSubnet(ops, subnet.networkId,
                IntIPv4.fromString(subnet.cidr, "/"));

        ops.add(zk.getDeleteOp(paths.getNeutronSubnetPath(subnet.id)));
        return subnet;
    }

    public void prepareUpdateSubnet(List<Op> ops, Subnet subnet)
            throws SerializationException, StateAccessException {

        BridgeDhcpZkManager.Subnet config =
                new BridgeDhcpZkManager.Subnet(subnet);
        dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, config);

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

        String path= paths.getNeutronSubnetsPath();
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
            if (!subnet.isIpv4()) continue;

            dhcpZkManager.prepareAddHost(
                    ops, subnet, new Host(port.macAddress, fixedIp.ipAddress));
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

        // Create DHCP host entries
        prepareCreateDhcpHostEntries(ops, port);

        return cfg;
    }

    public PortConfig prepareCreateDhcpPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        BridgePortConfig cfg = prepareCreateBridgePort(ops, port);

        // Add option 121 routes for metadata
        prepareCreateDhcpMetadataRoutes(ops, port.fixedIps);

        return cfg;
    }

    private void prepareAddMetadataOption121Route(List<Op> ops,
                                                  Subnet subnet, String ipAddr)
            throws SerializationException, StateAccessException {

        Opt121 opt121 = new Opt121(MetaDataService.IPv4_ADDRESS, ipAddr);

        BridgeDhcpZkManager.Subnet cfg =
                dhcpZkManager.getSubnet(subnet.networkId,
                        IntIPv4.fromString(subnet.cidr, "/"));

        if (!cfg.getOpt121Routes().contains(opt121)) {
            cfg.getOpt121Routes().add(opt121);
            dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, cfg);
        }
    }

    private void prepareCreateDhcpMetadataRoutes(List<Op> ops,
                                                 List<IPAllocation> fixedIps)
            throws SerializationException, StateAccessException {

        for (IPAllocation fixedIp : fixedIps) {
            Subnet subnet = getSubnet(fixedIp.subnetId);
            if (!subnet.isIpv4()) continue;

            prepareAddMetadataOption121Route(ops, subnet, fixedIp.ipAddress);
        }
    }

    public void prepareDeleteNeutronPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        String path = paths.getNeutronPortPath(port.id);
        ops.add(zk.getDeleteOp(path));

    }

    private void prepareDeleteDhcpHostEntries(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        for (IPAllocation ipAlloc : port.fixedIps) {
            Subnet subnet = getSubnet(ipAlloc.subnetId);
            dhcpZkManager.prepareDeleteHost(ops, subnet.networkId,
                    IntIPv4.fromString(subnet.cidr, "/"), port.macAddress);
        }
    }

    public PortConfig prepareDeletePortConfig(List<Op> ops, UUID portId)
            throws SerializationException, StateAccessException {
        PortConfig p = portZkManager.get(portId);
        portZkManager.prepareDelete(ops, p, true);
        return p;
    }

    public void prepareDeleteVifPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        // Remove DHCP mappings
        prepareDeleteDhcpHostEntries(ops, port);

        // Remove the port config
        prepareDeletePortConfig(ops, port.id);
    }

    private void prepareDeleteDhcpMetadataRoutes(List<Op> ops,
                                                 List<IPAllocation> fixedIps)
            throws SerializationException, StateAccessException {

        for (IPAllocation fixedIp : fixedIps) {
            Subnet subnet = getSubnet(fixedIp.subnetId);
            if (!subnet.isIpv4()) continue;

            BridgeDhcpZkManager.Subnet cfg =
                    dhcpZkManager.getSubnet(subnet.networkId,
                            IntIPv4.fromString(subnet.cidr, "/"));

            Opt121 opt121 = new Opt121(MetaDataService.IPv4_ADDRESS,
                    fixedIp.ipAddress);
            if (cfg.getOpt121Routes().remove(opt121)) {
                dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, cfg);
            }
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

        String path= paths.getNeutronPortsPath();
        Set<UUID> portIds = getUuidSet(path);

        List<Port> ports = new ArrayList<>();
        for (UUID portId : portIds) {
            ports.add(getPort(portId));
        }

        return ports;
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
            prepareCreateDhcpMetadataRoutes(ops, port.fixedIps);
        }
    }

    public void prepareUpdateNeutronPort(List<Op> ops, Port port)
            throws SerializationException {
        // Update the neutron port config
        String path = paths.getNeutronPortPath(port.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(port)));
    }
}
