/*
 * Copyright 2012, 2013 Midokura Europe SARL
 */

package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.neutron.Route;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;
import org.midonet.util.version.Since;

public class BridgeDhcpZkManager extends BaseZkManager {

    private static final Logger log = LoggerFactory
        .getLogger(BridgeDhcpZkManager.class);

    public static class Subnet {
        IntIPv4 subnetAddr;
        IntIPv4 serverAddr;
        List<IntIPv4> dnsServerAddrs;
        IntIPv4 defaultGateway;
        short interfaceMTU;
        List<Opt121> opt121Routes;

        @Since("1.4")
        Boolean enabled;

        /* Default constructor for deserialization. */
        public Subnet() {
        }
        public Subnet(IntIPv4 subnetAddr, IntIPv4 defaultGateway,
                      IntIPv4 serverAddr, List<IntIPv4> dnsServerAddrs,
                      short interfaceMTU, List<Opt121> opt121Routes) {
            this(subnetAddr, defaultGateway, serverAddr, dnsServerAddrs,
                    interfaceMTU, opt121Routes, true);
        }

        public Subnet(IntIPv4 subnetAddr, IntIPv4 defaultGateway,
                      IntIPv4 serverAddr, List<IntIPv4> dnsServerAddrs,
                      short interfaceMTU, List<Opt121> opt121Routes,
                      boolean enabled) {
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
            if (interfaceMTU != 0) {
                this.interfaceMTU = interfaceMTU;
            }
            this.dnsServerAddrs = dnsServerAddrs;
            this.defaultGateway = defaultGateway;
            this.opt121Routes = opt121Routes;
            this.enabled = enabled;
        }

        public Subnet(org.midonet.cluster.data.neutron.Subnet subnet) {

            this.subnetAddr = IntIPv4.fromString(subnet.cidr, "/");
            this.defaultGateway = subnet.gatewayIp == null ?
                null :
                IntIPv4.fromString(subnet.gatewayIp);

            if (subnet.hostRoutes != null) {
                this.opt121Routes = new ArrayList<>(subnet.hostRoutes.size());
                for (Route hostRoute : subnet.hostRoutes) {
                    this.opt121Routes.add(new BridgeDhcpZkManager.Opt121(
                            hostRoute.destination, hostRoute.nexthop));
                }
            }

            if (subnet.dnsNameservers != null) {
                this.dnsServerAddrs = new ArrayList<>(
                        subnet.dnsNameservers.size());
                for (String dnsServer : subnet.dnsNameservers) {
                    this.dnsServerAddrs.add(IntIPv4.fromString(dnsServer));
                }
            }
        }

        public IntIPv4 getDefaultGateway() {
            return defaultGateway;
        }

        public IntIPv4 getServerAddr() {
            return serverAddr;
        }

        public List<IntIPv4> getDnsServerAddrs() {
            return dnsServerAddrs;
        }

        public short getInterfaceMTU() {
            return interfaceMTU;
        }

        public List<Opt121> getOpt121Routes() {
            return opt121Routes;
        }

        public IntIPv4 getSubnetAddr() {
            return subnetAddr;
        }

        public Boolean isEnabled() { return enabled; }

        public void setDefaultGateway(IntIPv4 defaultGateway) {
            this.defaultGateway = defaultGateway;
        }

        public void setOpt121Routes(List<Opt121> opt121Routes) {
            this.opt121Routes = opt121Routes;
        }

        public void addOpt121Route(String rtDst, String addr) {
            Opt121 opt121 = new Opt121(rtDst, addr);
            if (opt121Routes == null) {
                opt121Routes = new ArrayList<>();
            }
            if (opt121Routes.contains(opt121)) {
                opt121Routes.add(opt121);
            }
        }

        public void removeOpt121Route(String metaDataAddr, String addr) {
            if (opt121Routes != null) {
                Opt121 opt121 = new Opt121(metaDataAddr, addr);
                opt121Routes.remove(opt121);
            }
        }

        public void setSubnetAddr(IntIPv4 subnetAddr) {
            this.subnetAddr = subnetAddr;
        }

        public void setServerAddr(IntIPv4 serverAddr) {
            this.serverAddr = serverAddr;
        }

        public void setDnsServerAddrs(List<IntIPv4> dnsServerAddrs) {
            this.dnsServerAddrs = dnsServerAddrs;
        }

        public void setInterfaceMTU(short interfaceMTU) {
            this.interfaceMTU = interfaceMTU;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return "Subnet{" +
                "subnetAddr=" + subnetAddr +
                ", serverAddr=" + serverAddr +
                ", dnsServerAddrs=" + dnsServerAddrs +
                ", defaultGateway=" + defaultGateway +
                ", interfaceMTU=" + interfaceMTU +
                ", opt121Routes=" + opt121Routes +
                ", enabled=" + enabled +
                '}';
        }
    }

    public static class Host {
        MAC mac;
        IPv4Addr ip;
        String name;

        /* Default constructor for deserialization. */
        public Host() {
        }

        public Host(MAC mac, IPv4Addr ip, String name) {
            this.mac = mac;
            this.ip = ip;
            this.name = name;
        }

        public Host(MAC mac, IPv4Addr ip) {
            this(mac, ip, null);
        }

        public Host(String mac, String ip) {
            this(MAC.fromString(mac), IPv4Addr.fromString(ip));
        }

        public MAC getMac() {
            return mac;
        }

        public IPv4Addr getIp() {
            return ip;
        }

        public String getName() {
            return name;
        }

        public void setIp(IPv4Addr ip) {
            this.ip = ip;
        }

        public void setMac(MAC mac) {
            this.mac = mac;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Host host = (Host) o;

            if (ip != null ? !ip.equals(host.ip) : host.ip != null)
                return false;
            if (mac != null ? !mac.equals(host.mac) : host.mac != null)
                return false;
            if (name != null ? !name.equals(host.name) : host.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = mac != null ? mac.hashCode() : 0;
            result = 31 * result + (ip != null ? ip.hashCode() : 0);
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
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

        public Opt121(String rtDstSubnet, String gateway) {
            this.rtDstSubnet = IntIPv4.fromString(rtDstSubnet, "/");
            this.gateway = IntIPv4.fromString(gateway, "/");
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!rtDstSubnet.equals(((Opt121) o).rtDstSubnet)) return false;
            if (!gateway.equals(((Opt121) o).gateway)) return false;
            return true;
        }
    }

    /**
     * Initializes a BridgeDhcpZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public BridgeDhcpZkManager(ZkManager zk, PathBuilder paths,
                               Serializer serializer) {
        super(zk, paths, serializer);
    }

    public void prepareCreateSubnet(List<Op> ops, UUID bridgeId, Subnet subnet)
            throws SerializationException {
        ops.add(Op.create(paths.getBridgeDhcpSubnetPath(
                        bridgeId, subnet.getSubnetAddr()),
                serializer.serialize(subnet),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBridgeDhcpHostsPath(
                bridgeId, subnet.getSubnetAddr()), null,
            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public void createSubnet(UUID bridgeId, Subnet subnet)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        prepareCreateSubnet(ops, bridgeId, subnet);
        zk.multi(ops);
    }

    public void prepareUpdateSubnet(List<Op> ops, UUID bridgeId, Subnet subnet)
            throws StateAccessException, SerializationException {
        ops.add(zk.getSetDataOp(
            paths.getBridgeDhcpSubnetPath(
                bridgeId, subnet.getSubnetAddr()),
            serializer.serialize(subnet)));
    }

    public void updateSubnet(UUID bridgeId, Subnet subnet)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        prepareUpdateSubnet(ops, bridgeId, subnet);
        zk.multi(ops);
    }

    public Subnet getSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getBridgeDhcpSubnetPath(bridgeId,
            subnetAddr), null);
        return serializer.deserialize(data, Subnet.class);
    }

    public boolean existsSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        return zk.exists(paths.getBridgeDhcpSubnetPath(bridgeId,
                subnetAddr));
    }

    public void prepareDeleteSubnet(List<Op> ops, UUID bridgeId,
                                    IntIPv4 subnetAddr)
            throws StateAccessException {

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
    }

    public void deleteSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        List<Op> ops = new ArrayList<>();
        prepareDeleteSubnet(ops, bridgeId, subnetAddr);
        zk.multi(ops);
    }

    public List<IntIPv4> listSubnets(UUID bridgeId)
            throws StateAccessException {
        Set<String> addrStrings = zk.getChildren(
                paths.getBridgeDhcpPath(bridgeId), null);
        List<IntIPv4> addrs = new ArrayList<IntIPv4>();
        for (String addrStr : addrStrings)
            addrs.add(IntIPv4.fromString(addrStr));
        return addrs;
    }

    public List<Subnet> getSubnets(UUID bridgeId)
            throws StateAccessException, SerializationException {
        Set<String> addrStrings = zk.getChildren(
                paths.getBridgeDhcpPath(bridgeId));
        List<Subnet> subnets = new ArrayList<Subnet>();
        for (String addrStr : addrStrings)
            subnets.add(getSubnet(bridgeId, IntIPv4.fromString(addrStr)));
        return subnets;
    }

    public List<Subnet> getEnabledSubnets(UUID bridgeId)
            throws StateAccessException, SerializationException {
        List<Subnet> subnets = getSubnets(bridgeId);
        List<Subnet> enabledSubnets = new ArrayList<>(subnets.size());
        for (Subnet subnet : subnets) {
            // This check is because of backward-compatibility with data
            // created in v1.3 or earlier where 'enabled' could be null.
            // Null enabled field, which could only exist in the old data, is
            // treated as true.
            if (subnet.enabled == null || subnet.enabled) {
                enabledSubnets.add(subnet);
            }
        }
        return enabledSubnets;
    }

    public void prepareAddHost(List<Op> ops, UUID bridgeId,IntIPv4 subnetAddr,
                               Host host)
            throws SerializationException {
        ops.add(zk.getPersistentCreateOp(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, host.getMac()),
                serializer.serialize(host)));
    }

    public void prepareAddHost(List<Op> ops, UUID bridgeId, String subnetAddr,
                               Host host) throws SerializationException {
        IntIPv4 cidr = IntIPv4.fromString(subnetAddr, "/");
        prepareAddHost(ops, bridgeId, cidr, host);
    }

    public void prepareAddHost(List<Op> ops,
                               org.midonet.cluster.data.neutron.Subnet subnet,
                               Host host)
            throws SerializationException {
        prepareAddHost(ops, subnet.networkId, subnet.cidr, host);
    }

    public void addHost(UUID bridgeId, IntIPv4 subnetAddr, Host host)
            throws StateAccessException, SerializationException {
        zk.addPersistent(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, host.getMac()),
                serializer.serialize(host));
    }

    public void updateHost(UUID bridgeId, IntIPv4 subnetAddr, Host host)
            throws StateAccessException, SerializationException {
        zk.update(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, host.getMac()),
                serializer.serialize(host));
    }

    public Host getHost(UUID bridgeId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, MAC.fromString(mac)), null);
        return serializer.deserialize(data, Host.class);
    }

    public void prepareDeleteHost(List<Op> ops, UUID bridgeId,
                                  IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        ops.add(zk.getDeleteOp(paths.getBridgeDhcpHostPath(bridgeId,
                subnetAddr, MAC.fromString(mac))));
    }

    public void deleteHost(UUID bridgId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        zk.delete(paths.getBridgeDhcpHostPath(bridgId, subnetAddr,
                MAC.fromString(mac)));
    }

    public boolean existsHost(UUID bridgeId, IntIPv4 subnetAddr, String mac)
            throws StateAccessException {
        return zk.exists(paths.getBridgeDhcpHostPath(
                bridgeId, subnetAddr, MAC.fromString(mac)));
    }

    public List<MAC> listHosts(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        Set<String> macStrings = zk.getChildren(
                paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr));
        List<MAC> macs = new ArrayList<MAC>();
        for (String macStr : macStrings)
            macs.add(MAC.fromString(macStr));
        return macs;
    }

    public List<Host> getHosts(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException, SerializationException {
        Set<String> macStrings = zk.getChildren(
                paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr));
        List<Host> hosts = new ArrayList<Host>();
        for (String macStr : macStrings)
            hosts.add(getHost(bridgeId, subnetAddr, macStr));
        return hosts;
    }
}
