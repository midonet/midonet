/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;

public class BridgeDhcpZkManager extends ZkManager {

    public static class Subnet {
        IntIPv4 subnetAddr;
        IntIPv4 serverAddr; // TODO: implement this.
        IntIPv4 defaultGateway;
        List<Opt121> opt121Routes;

        /* Default constructor for deserialization. */
        public Subnet() {
        }

        public Subnet(IntIPv4 subnetAddr, IntIPv4 defaultGateway,
                      List<Opt121> opt121Routes) {
            this.subnetAddr = subnetAddr;
            this.defaultGateway = defaultGateway;
            this.opt121Routes = opt121Routes;
        }

        public IntIPv4 getDefaultGateway() {
            return defaultGateway;
        }

        public List<Opt121> getOpt121Routes() {
            return opt121Routes;
        }

        public IntIPv4 getSubnetAddr() {
            return subnetAddr;
        }

        public void setDefaultGateway(IntIPv4 defaultGateway) {
            this.defaultGateway = defaultGateway;
        }

        public void setOpt121Routes(List<Opt121> opt121Routes) {
            this.opt121Routes = opt121Routes;
        }

        public void setSubnetAddr(IntIPv4 subnetAddr) {
            this.subnetAddr = subnetAddr;
        }
    }

    public static class Host {
        MAC mac;
        IntIPv4 ip;
        String name;

        /* Default constructor for deserialization. */
        public Host() {
        }

        public Host(MAC mac, IntIPv4 ip, String name) {
            this.mac = mac;
            this.ip = ip;
            this.name = name;
        }

        public MAC getMac() {
            return mac;
        }

        public IntIPv4 getIp() {
            return ip;
        }

        public String getName() {
            return name;
        }

        public void setIp(IntIPv4 ip) {
            this.ip = ip;
        }

        public void setMac(MAC mac) {
            this.mac = mac;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Opt121 {
        IntIPv4 rtDstSubnet;
        IntIPv4 gateway;

        /* Default constructor for deserialization. */
        public Opt121() {
        }

        public Opt121(IntIPv4 rtDstSubnet, IntIPv4 gateway) {
            this.rtDstSubnet = rtDstSubnet;
            this.gateway = gateway;
        }

        public IntIPv4 getGateway() {
            return gateway;
        }

        public IntIPv4 getRtDstSubnet() {
            return rtDstSubnet;
        }

        public void setGateway(IntIPv4 gateway) {
            this.gateway = gateway;
        }

        public void setRtDstSubnet(IntIPv4 rtDstSubnet) {
            this.rtDstSubnet = rtDstSubnet;
        }
    }

    /**
     * Initializes a BridgeDhcpZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     *
     * @param zk
     *            ZooKeeper object.
     * @param basePath
     *            The root path.
     */
    public BridgeDhcpZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public void createSubnet(UUID bridgeId, Subnet subnet)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getBridgeDhcpSubnetPath(
                    bridgeId, subnet.getSubnetAddr()),
                    serialize(subnet), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize dhcp subnet", e, Subnet.class);
        }
        ops.add(Op.create(pathManager.getBridgeDhcpHostsPath(
                bridgeId, subnet.getSubnetAddr()), null,
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        multi(ops);
    }

    public void updateSubnet(UUID bridgeId, Subnet subnet)
            throws StateAccessException {
        try {
            update(pathManager.getBridgeDhcpSubnetPath(bridgeId,
                    subnet.getSubnetAddr()), serialize(subnet));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize dhcp subnet", e, Subnet.class);
        }
    }

    public Subnet getSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        byte[] data = get(pathManager.getBridgeDhcpSubnetPath(bridgeId,
                subnetAddr), null);
        Subnet subnet = null;
        try {
            subnet = deserialize(data, Subnet.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize dhcp subnet " + subnetAddr +
                            " to Subnet object", e, Subnet.class);
        }
        return subnet;
    }

    public void deleteSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        // Delete the hostAssignments
        List<MAC> hosts = listHosts(bridgeId, subnetAddr);
        for (MAC mac : hosts)
            ops.add(Op.delete(pathManager.getBridgeDhcpHostPath(
                    bridgeId, subnetAddr, mac), -1));
        // Delete the 'hosts' subdirectory.
        ops.add(Op.delete(
                pathManager.getBridgeDhcpHostsPath(bridgeId, subnetAddr), -1));
        // Delete the subnet's root directory.
        ops.add(Op.delete(
                pathManager.getBridgeDhcpSubnetPath(bridgeId, subnetAddr), -1));
        multi(ops);
    }

    public List<IntIPv4> listSubnets(UUID bridgeId)
            throws StateAccessException {
        Set<String> addrStrings = getChildren(
                pathManager.getBridgeDhcpPath(bridgeId), null);
        List<IntIPv4> addrs = new ArrayList<IntIPv4>();
        for (String addrStr : addrStrings)
            addrs.add(IntIPv4.fromString(addrStr));
        return addrs;
    }

    public List<Subnet> getSubnets(UUID bridgeId)
            throws StateAccessException {
        Set<String> addrStrings = getChildren(
                pathManager.getBridgeDhcpPath(bridgeId));
        List<Subnet> subnets = new ArrayList<Subnet>();
        for (String addrStr : addrStrings)
            subnets.add(getSubnet(bridgeId, IntIPv4.fromString(addrStr)));
        return subnets;
    }

    public void addHost(UUID bridgeId, IntIPv4 subnetAddr, Host host)
            throws StateAccessException {
        try {
            addPersistent(pathManager.getBridgeDhcpHostPath(
                    bridgeId, subnetAddr, host.getMac()), serialize(host));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize dhcp host", e, Host.class);
        }
    }

    public void updateHost(UUID bridgeId, IntIPv4 subnetAddr, Host host)
            throws StateAccessException {
        try {
            update(pathManager.getBridgeDhcpHostPath(
                    bridgeId, subnetAddr, host.getMac()), serialize(host));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize dhcp host", e, Host.class);
        }
    }

    public Host getHost(UUID bridgeId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        byte[] data = get(pathManager.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, MAC.fromString(mac)), null);
        Host host = null;
        try {
            host = deserialize(data, Host.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize dhcp host " + mac + " on subnet " +
                            subnetAddr + " to HOST object", e, Subnet.class);
        }
        return host;
    }

    public void deleteHost(UUID bridgId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        delete(pathManager.getBridgeDhcpHostPath(bridgId, subnetAddr,
                MAC.fromString(mac)));
    }

    public List<MAC> listHosts(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        Set<String> macStrings = getChildren(
                pathManager.getBridgeDhcpHostsPath(bridgeId, subnetAddr));
        List<MAC> macs = new ArrayList<MAC>();
        for (String macStr : macStrings)
            macs.add(MAC.fromString(macStr));
        return macs;
    }

    public List<Host> getHosts(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        Set<String> macStrings = getChildren(
                pathManager.getBridgeDhcpHostsPath(bridgeId, subnetAddr));
        List<Host> hosts = new ArrayList<Host>();
        for (String macStr : macStrings)
            hosts.add(getHost(bridgeId, subnetAddr, macStr));
        return hosts;
    }
}
