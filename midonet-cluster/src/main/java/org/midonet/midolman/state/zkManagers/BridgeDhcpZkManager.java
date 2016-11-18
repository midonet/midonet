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

package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.dhcp.ExtraDhcpOpt;
import org.midonet.cluster.rest_api.neutron.models.Route;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.version.Since;

public class BridgeDhcpZkManager extends BaseZkManager {

    public static class Subnet {
        IPv4Subnet subnetAddr;
        IPv4Addr serverAddr;
        List<IPv4Addr> dnsServerAddrs;
        IPv4Addr defaultGateway;
        short interfaceMTU;
        List<Opt121> opt121Routes;

        @Since("1.4")
        Boolean enabled;

        /* Default constructor for deserialization. */
        public Subnet() {
        }

        public Subnet(IPv4Subnet subnetAddr, IPv4Addr defaultGateway,
                      IPv4Addr serverAddr, List<IPv4Addr> dnsServerAddrs,
                      short interfaceMTU, List<Opt121> opt121Routes) {
            this(subnetAddr, defaultGateway, serverAddr, dnsServerAddrs,
                    interfaceMTU, opt121Routes, true);
        }

        public Subnet(IPv4Subnet subnetAddr, IPv4Addr defaultGateway,
                      IPv4Addr serverAddr, List<IPv4Addr> dnsServerAddrs,
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
                    this.serverAddr = IPv4Addr.fromInt(
                            subnetAddr.toBroadcastAddress().toInt() - 1);
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

        public Subnet(org.midonet.cluster.rest_api.neutron.models.Subnet subnet) {

            this.serverAddr = subnet.gatewayIp == null ? null :
                IPv4Addr.fromString(subnet.gatewayIp);
            this.subnetAddr = IPv4Subnet.fromCidr(subnet.cidr);
            this.defaultGateway = subnet.gatewayIp == null ? null :
                IPv4Addr.fromString(subnet.gatewayIp);
            this.enabled = subnet.enableDhcp;

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
                    this.dnsServerAddrs.add(IPv4Addr.fromString(dnsServer));
                }
            }
        }

        public IPv4Addr getDefaultGateway() {
            return defaultGateway;
        }

        public IPv4Addr getServerAddr() {
            return serverAddr;
        }

        public List<IPv4Addr> getDnsServerAddrs() {
            return dnsServerAddrs;
        }

        public short getInterfaceMTU() {
            return interfaceMTU;
        }

        public List<Opt121> getOpt121Routes() {
            return opt121Routes;
        }

        public IPv4Subnet getSubnetAddr() {
            return subnetAddr;
        }

        public Boolean isEnabled() { return enabled; }

        public void setDefaultGateway(IPv4Addr defaultGateway) {
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
            if (!opt121Routes.contains(opt121)) {
                opt121Routes.add(opt121);
            }
        }

        public void removeOpt121Route(String rtDst, String addr) {
            if (opt121Routes != null) {
                Opt121 opt121 = new Opt121(rtDst, addr);
                opt121Routes.remove(opt121);
            }
        }

        public void setSubnetAddr(IPv4Subnet subnetAddr) {
            this.subnetAddr = subnetAddr;
        }

        public void setServerAddr(IPv4Addr serverAddr) {
            this.serverAddr = serverAddr;
        }

        public void setDnsServerAddrs(List<IPv4Addr> dnsServerAddrs) {
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

        @Since("1.8")
        @Nonnull
        List<ExtraDhcpOpt> extraDhcpOpts = new ArrayList<>();

        /* Default constructor for deserialization. */
        public Host() {
        }

        public Host(MAC mac, IPv4Addr ip, String name) {
            this.mac = mac;
            this.ip = ip;
            this.name = name;
        }

        public Host(MAC mac, IPv4Addr ip, String name,
                    @Nonnull List<ExtraDhcpOpt> extraDhcpOpts) {
            this(mac, ip, name);
            Preconditions.checkNotNull(extraDhcpOpts,
                    "Extra DHCP options should not null. Use empty list " +
                            "instead to express absense of it.");
            this.extraDhcpOpts = extraDhcpOpts;
        }

        public Host(MAC mac, IPv4Addr ip,
                    @Nonnull List<ExtraDhcpOpt> extraDhcpOpts) {
            this(mac, ip, null, extraDhcpOpts);
        }

        public Host(String mac, String ip,
                    @Nonnull List<ExtraDhcpOpt> extraDhcpOpts) {
            this(MAC.fromString(mac), IPv4Addr.fromString(ip), extraDhcpOpts);
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

        public List<ExtraDhcpOpt> getExtraDhcpOpts() {
            return extraDhcpOpts;
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

        public void setExtraDhcpOpts(List<ExtraDhcpOpt> extraDhcpOpts) {
            this.extraDhcpOpts = extraDhcpOpts;
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
            if (extraDhcpOpts != null ?
                !extraDhcpOpts.equals(host.extraDhcpOpts) :
                host.extraDhcpOpts != null)
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
        IPv4Subnet rtDstSubnet;
        IPv4Addr gateway;

        /* Default constructor for deserialization. */
        public Opt121() {
        }

        public Opt121(IPv4Subnet rtDstSubnet, IPv4Addr gateway) {
            this.rtDstSubnet = rtDstSubnet;
            this.gateway = gateway;
        }

        public Opt121(String rtDstSubnet, String gateway) {
            this.rtDstSubnet = IPv4Subnet.fromCidr(rtDstSubnet);
            this.gateway = IPv4Addr.fromString(gateway);
        }

        public IPv4Addr getGateway() {
            return gateway;
        }

        public IPv4Subnet getRtDstSubnet() {
            return rtDstSubnet;
        }

        public void setGateway(IPv4Addr gateway) {
            this.gateway = gateway;
        }

        public void setRtDstSubnet(IPv4Subnet rtDstSubnet) {
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
     */
    @Inject
    public BridgeDhcpZkManager(ZkManager zk, PathBuilder paths,
                               Serializer serializer) {
        super(zk, paths, serializer);
    }

    public void prepareCreateSubnet(List<Op> ops, UUID bridgeId, Subnet subnet)
            throws SerializationException {
        String subnetPath =
            paths.getBridgeDhcpSubnetPath(bridgeId, subnet.getSubnetAddr());
        ops.add(Op.create(subnetPath, serializer.serialize(subnet),
                          ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        String hostPath =
            paths.getBridgeDhcpHostsPath(bridgeId, subnet.getSubnetAddr());
        ops.add(Op.create(hostPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                          CreateMode.PERSISTENT));
    }

    public Subnet getSubnet(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {
        String path = paths.getBridgeDhcpSubnetPath(bridgeId, subnetAddr);
        byte[] data = zk.get(path, null);
        return serializer.deserialize(data, Subnet.class);
    }

    public boolean existsSubnet(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException {
        String path = paths.getBridgeDhcpSubnetPath(bridgeId, subnetAddr);
        return zk.exists(path);
    }

    public List<IPv4Subnet> listSubnets(UUID bridgeId)
            throws StateAccessException {
        String path = paths.getBridgeDhcpPath(bridgeId);
        List<IPv4Subnet> addrs = new ArrayList<>();
        for (String addrStr : zk.getChildren(path , null)) {
            addrs.add(IPv4Subnet.fromUriCidr(addrStr));
        }
        return addrs;
    }

    public List<Subnet> getSubnets(UUID bridgeId)
            throws StateAccessException, SerializationException {
        String path = paths.getBridgeDhcpPath(bridgeId);
        List<Subnet> subnets = new ArrayList<>();
        for (String addrStr : zk.getChildren(path)) {
            subnets.add(getSubnet(bridgeId, IPv4Subnet.fromUriCidr(addrStr)));
        }
        return subnets;
    }

    public Host getHost(UUID bridgeId, IPv4Subnet subnetAddr, String mac)
            throws StateAccessException, SerializationException {
        String path = paths.getBridgeDhcpHostPath(bridgeId, subnetAddr,
                                                  MAC.fromString(mac));
        byte[] data = zk.get(path, null);
        return serializer.deserialize(data, Host.class);
    }

    public List<MAC> listHosts(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException {
        String path = paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr);
        List<MAC> macs = new ArrayList<>();
        for (String macStr : zk.getChildren(path))
            macs.add(MAC.fromString(macStr));
        return macs;
    }

    public List<Host> getHosts(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {
        String path = paths.getBridgeDhcpHostsPath(bridgeId, subnetAddr);
        List<Host> hosts = new ArrayList<>();
        for (String macStr : zk.getChildren(path))
            hosts.add(getHost(bridgeId, subnetAddr, macStr));
        return hosts;
    }
}
