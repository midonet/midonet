/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.state.zkManagers;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BridgeDhcpZkManager extends ZkManager {

    private static final Logger log = LoggerFactory
        .getLogger(BridgeDhcpZkManager.class);

    public static class Subnet {
        IntIPv4 subnetAddr;
        IntIPv4 serverAddr;
        IntIPv4 dnsServerAddr;
        IntIPv4 defaultGateway;
        List<Opt121> opt121Routes;

        /* Default constructor for deserialization. */
        public Subnet() {
        }

        public Subnet(IntIPv4 subnetAddr, IntIPv4 defaultGateway,
                      IntIPv4 serverAddr, IntIPv4 dnsServerAddr,
                      List<Opt121> opt121Routes) {
            this.subnetAddr = subnetAddr;
            if (serverAddr != null) {
                this.serverAddr = serverAddr;
            } else {
                // If not configured, first attempt to set it to the default GW
                if (defaultGateway != null) {
                    this.serverAddr = defaultGateway;
                } else {
                    // hard-code it to network bcast addr - 1
                    this.serverAddr = new IntIPv4(
                            subnetAddr.toBroadcastAddress().addressAsInt() - 1,
                            subnetAddr.getMaskLength());
                }
            }
            this.dnsServerAddr = dnsServerAddr;
            this.defaultGateway = defaultGateway;
            this.opt121Routes = opt121Routes;
        }

        public IntIPv4 getDefaultGateway() {
            return defaultGateway;
        }

        public IntIPv4 getServerAddr() {
            return serverAddr;
        }

        public IntIPv4 getDnsServerAddr() {
            return dnsServerAddr;
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

        public void setServerAddr(IntIPv4 serverAddr) {
            this.serverAddr = serverAddr;
        }

        public void setDnsServerAddr(IntIPv4 dnsServerAddr) {
            this.dnsServerAddr = dnsServerAddr;
        }

        @Override
        public String toString() {
            return "Subnet{" +
                "subnetAddr=" + subnetAddr +
                ", serverAddr=" + serverAddr +
                ", dnsServerAddr=" + dnsServerAddr +
                ", defaultGateway=" + defaultGateway +
                ", opt121Routes=" + opt121Routes +
                '}';
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
        ops.add(Op.create(paths.getBridgeDhcpSubnetPath(
                bridgeId, subnet.getSubnetAddr()),
                serializer.serialize(subnet), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgeDhcpHostsPath(
            bridgeId, subnet.getSubnetAddr()), null,
                          ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        multi(ops);
    }

    public void updateSubnet(UUID bridgeId, Subnet subnet)
            throws StateAccessException {
        update(paths.getBridgeDhcpSubnetPath(bridgeId,
                subnet.getSubnetAddr()), serializer.serialize(subnet));
    }

    public Subnet getSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        byte[] data = get(paths.getBridgeDhcpSubnetPath(bridgeId,
                subnetAddr), null);
        return serializer.deserialize(data, Subnet.class);
    }

    public boolean existsSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        return exists(paths.getBridgeDhcpSubnetPath(bridgeId,
                subnetAddr));
    }

    public void deleteSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        // Delete the hostAssignments
        List<MAC> hosts = listHosts(bridgeId, subnetAddr);
        for (MAC mac : hosts)
            ops.add(Op.delete(paths.getBridgeDhcpHostPath(
                    bridgeId, subnetAddr, mac), -1));
        // Delete the 'hosts' subdirectory.
        ops.add(Op.delete(
                paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr), -1));
        // Delete the subnet's root directory.
        ops.add(Op.delete(
                paths.getBridgeDhcpSubnetPath(bridgeId, subnetAddr), -1));
        multi(ops);
    }

    public List<IntIPv4> listSubnets(UUID bridgeId)
            throws StateAccessException {
        Set<String> addrStrings = getChildren(
                paths.getBridgeDhcpPath(bridgeId), null);
        List<IntIPv4> addrs = new ArrayList<IntIPv4>();
        for (String addrStr : addrStrings)
            addrs.add(IntIPv4.fromString(addrStr));
        return addrs;
    }

    public List<Subnet> getSubnets(UUID bridgeId)
            throws StateAccessException {
        Set<String> addrStrings = getChildren(
                paths.getBridgeDhcpPath(bridgeId));
        List<Subnet> subnets = new ArrayList<Subnet>();
        for (String addrStr : addrStrings)
            subnets.add(getSubnet(bridgeId, IntIPv4.fromString(addrStr)));
        return subnets;
    }

    public void addHost(UUID bridgeId, IntIPv4 subnetAddr, Host host)
            throws StateAccessException {
        addPersistent(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, host.getMac()), serializer.serialize(host));
    }

    public void updateHost(UUID bridgeId, IntIPv4 subnetAddr, Host host)
            throws StateAccessException {
        update(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, host.getMac()), serializer.serialize(host));
    }

    public Host getHost(UUID bridgeId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        byte[] data = get(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, MAC.fromString(mac)), null);
        return serializer.deserialize(data, Host.class);
    }

    public void deleteHost(UUID bridgId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        delete(paths.getBridgeDhcpHostPath(bridgId, subnetAddr,
                MAC.fromString(mac)));
    }

    public boolean existsHost(UUID bridgeId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        return exists(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, MAC.fromString(mac)));
    }

    public List<MAC> listHosts(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        Set<String> macStrings = getChildren(
                paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr));
        List<MAC> macs = new ArrayList<MAC>();
        for (String macStr : macStrings)
            macs.add(MAC.fromString(macStr));
        return macs;
    }

    public List<Host> getHosts(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        Set<String> macStrings = getChildren(
                paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr));
        List<Host> hosts = new ArrayList<Host>();
        for (String macStr : macStrings)
            hosts.add(getHost(bridgeId, subnetAddr, macStr));
        return hosts;
    }
}
