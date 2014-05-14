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
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
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

        BridgeConfig config = ConfigFactory.createBridge(network);
        ops.addAll(bridgeZkManager.prepareBridgeCreate(network.id, config));
    }

    public void prepareDeleteNetwork(List<Op> ops, UUID id)
            throws SerializationException, StateAccessException {

        Network network = getNetwork(id);
        if (network == null) {
            return;
        }

        ops.addAll(bridgeZkManager.prepareBridgeDelete(id));

        String path = paths.getNeutronNetworkPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareUpdateNetwork(List<Op> ops, Network network)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {

        UUID id = network.id;

        BridgeZkManager.BridgeConfig config = ConfigFactory.createBridge(
                network);
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
        Set<String> networkIds = zk.getChildren(path);

        List<Network> networks = new ArrayList<>();
        for (String networkId : networkIds) {
            networks.add(getNetwork(UUID.fromString(networkId)));
        }

        return networks;
    }

    /** Subnet methods **/

    public void prepareCreateSubnet(List<Op> ops, Subnet subnet)
            throws SerializationException, StateAccessException {

        BridgeDhcpZkManager.Subnet config =
                ConfigFactory.createDhcpSubnet(subnet);
        dhcpZkManager.prepareCreateSubnet(ops, subnet.networkId, config);

        String path = paths.getNeutronSubnetPath(subnet.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(subnet)));
    }

    public void prepareDeleteSubnet(List<Op> ops, UUID id)
            throws StateAccessException, SerializationException {

        Subnet subnet = getSubnet(id);
        if (subnet == null) {
            return;
        }

        dhcpZkManager.prepareDeleteSubnet(ops, subnet.networkId,
                IntIPv4.fromString(subnet.cidr, "/"));

        ops.add(zk.getDeleteOp(paths.getNeutronSubnetPath(subnet.id)));
    }

    public void prepareUpdateSubnet(List<Op> ops, Subnet subnet)
            throws SerializationException, StateAccessException {

        BridgeDhcpZkManager.Subnet config = ConfigFactory.createDhcpSubnet(
                subnet);
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
        Set<String> subnetIds = zk.getChildren(path);

        List<Subnet> subnets = new ArrayList<>();
        for (String subnetId : subnetIds) {
            subnets.add(getSubnet(UUID.fromString(subnetId)));
        }

        return subnets;
    }

    private void prepareDhcpHostEntry(List<Op> ops, Subnet subnet,
                                      String macAddress, String ipAddress)
            throws SerializationException {

        BridgeDhcpZkManager.Host host = ConfigFactory.createDhcpHost(
                macAddress, ipAddress);
        IntIPv4 cidr = IntIPv4.fromString(subnet.cidr, "/");
        dhcpZkManager.prepareAddHost(ops, subnet.networkId, cidr, host);
    }

    private void prepareCreateDhcpHostEntries(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {
        for (IPAllocation fixedIp : port.fixedIps) {
            Subnet subnet = getSubnet(fixedIp.subnetId);
            if (!subnet.isIpv4()) continue;

            prepareDhcpHostEntry(ops, subnet, port.macAddress,
                    fixedIp.ipAddress);
        }
    }

    public void prepareCreateNeutronPort(List<Op> ops, Port port)
            throws SerializationException {

        String path = paths.getNeutronPortPath(port.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(port)));

    }

    public void prepareCreateVifPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        // Create DHCP host entries
        prepareCreateDhcpHostEntries(ops, port);

        // Create the Bridge port
        PortDirectory.BridgePortConfig cfg = ConfigFactory.createBridgePort(
                port.networkId);
        ops.addAll(portZkManager.prepareCreate(port.id, cfg));

        prepareCreateNeutronPort(ops, port);
    }

    private void prepareAddMetadataOption121Route(List<Op> ops,
                                                  Subnet subnet, String ipAddr)
            throws SerializationException, StateAccessException {

        BridgeDhcpZkManager.Opt121 opt121 = ConfigFactory.createDhcpOpt121(
                MetaDataService.IPv4_ADDRESS, ipAddr);

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

    public void prepareCreateDhcpPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        // Add option 121 routes for metadata
        prepareCreateDhcpMetadataRoutes(ops, port.fixedIps);

        ops.addAll(portZkManager.prepareCreate(
                port.id, ConfigFactory.createBridgePort(port.networkId)));

        prepareCreateNeutronPort(ops, port);
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

    public void prepareDeleteVifPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        // Remove Neutron port
        prepareDeleteNeutronPort(ops, port);

        // Remove DHCP mappings
        prepareDeleteDhcpHostEntries(ops, port);

        // Remove the port config
        PortConfig config = portZkManager.get(port.id);
        ops.addAll(portZkManager.prepareDelete(port.id, config));
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

            BridgeDhcpZkManager.Opt121 opt121 =
                    ConfigFactory.createDhcpOpt121(
                            MetaDataService.IPv4_ADDRESS, fixedIp.ipAddress);

            if (cfg.getOpt121Routes().remove(opt121)) {
                dhcpZkManager.prepareUpdateSubnet(ops, subnet.networkId, cfg);
            }
        }
    }

    public void prepareDeleteDhcpPort(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        // Remove Neutron port
        prepareDeleteNeutronPort(ops, port);

        ops.addAll(portZkManager.prepareDelete(port.id));
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

    public List<Port> getPorts()
            throws StateAccessException, SerializationException {

        String path= paths.getNeutronPortsPath();
        Set<String> portIds = zk.getChildren(path);

        List<Port> ports = new ArrayList<>();
        for (String portId : portIds) {
            ports.add(getPort(UUID.fromString(portId)));
        }

        return ports;
    }

    public void prepareUpdateVifPort(List<Op> ops, Port newPort)
            throws StateAccessException, SerializationException {

        // This should throw NoStatePathException
        portZkManager.prepareUpdatePortAdminState(ops, newPort.id,
                newPort.adminStateUp);


        // If there are fixed IPs, adjust the DHCP host entries
        if (newPort.fixedIps != null) {

            // Remove and re-add DHCP host mappings
            Port p = getPort(newPort.id);
            prepareDeleteDhcpHostEntries(ops, p);
            prepareCreateDhcpHostEntries(ops, newPort);
        }

        // Update the neutron port config
        String path = paths.getNeutronPortPath(newPort.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(newPort)));
    }

    public void prepareUpdateDhcpPort(List<Op> ops, Port newPort)
            throws StateAccessException, SerializationException {

        // This should throw NoStatePathException
        portZkManager.prepareUpdatePortAdminState(ops, newPort.id,
                newPort.adminStateUp);

        // If 'fixed_ips' are specified, they are new IPs to be assigned.
        if (newPort.fixedIps != null) {

            // Add new metadata DHCP option routes
            prepareCreateDhcpMetadataRoutes(ops, newPort.fixedIps);
        }

        // Update the neutron port config
        String path = paths.getNeutronPortPath(newPort.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(newPort)));
    }

}
