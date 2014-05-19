/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.midolman.rules.*;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.packets.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Contains factory methods for constructing data objects.
 */
public class ConfigFactory {

    private ConfigFactory() {}

    /**
     * Construct a Bridge object from a Network object.
     *
     * @param network Network object
     * @return Bridge object
     */
    public static BridgeConfig createBridge(Network network) {

        BridgeConfig config = new BridgeConfig();
        config.adminStateUp = network.adminStateUp;
        config.name = network.name;
        return config;
    }

    public static BridgeDhcpZkManager.Subnet createDhcpSubnet(Subnet subnet) {

        BridgeDhcpZkManager.Subnet dhcpSubnet =
                new BridgeDhcpZkManager.Subnet();

        dhcpSubnet.setSubnetAddr(IntIPv4.fromString(subnet.cidr, "/"));
        dhcpSubnet.setDefaultGateway(IntIPv4.fromString(subnet.gatewayIp));

        if (subnet.hostRoutes != null) {
            List<BridgeDhcpZkManager.Opt121> routes = new ArrayList<>();
            for (Route hostRoute : subnet.hostRoutes) {
                routes.add(createDhcpOpt121(
                        hostRoute.destination, hostRoute.nexthop));
            }
            dhcpSubnet.setOpt121Routes(routes);
        }

        if (subnet.dnsNameservers != null) {
            List<IntIPv4> servers = new ArrayList<>();
            for (String dnsServer : subnet.dnsNameservers) {
                servers.add(IntIPv4.fromString(dnsServer));
            }
            dhcpSubnet.setDnsServerAddrs(servers);
        }

        return dhcpSubnet;
    }

    public static BridgeDhcpZkManager.Opt121 createDhcpOpt121(
            String destination, String nexthop) {
        return new BridgeDhcpZkManager.Opt121(
                IntIPv4.fromString(destination, "/"),
                IntIPv4.fromString(nexthop));
    }

    public static BridgeDhcpZkManager.Host createDhcpHost(
            String macAddress, String ipAddress) {

        MAC mac = MAC.fromString(macAddress);
        IntIPv4 ipAddr = IntIPv4.fromString(ipAddress);
        return new BridgeDhcpZkManager.Host(mac, ipAddr, null);
    }

    public static PortDirectory.BridgePortConfig createBridgePort(
            UUID networkId) {
        PortDirectory.BridgePortConfig cfg =
                new PortDirectory.BridgePortConfig();

        cfg.adminStateUp = true;
        cfg.device_id = networkId;
        return cfg;
    }

    public static ChainZkManager.ChainConfig createChain(String name) {
        ChainZkManager.ChainConfig config = new ChainZkManager.ChainConfig();
        config.name = name;
        return config;
    }

    public static Rule createAcceptRule(SecurityGroupRule sgRule,
                                        UUID chainId) {

        Condition cond = new Condition();
        cond.nwProto = sgRule.protocolNumber();
        cond.dlType = sgRule.ethertype();
        cond.matchForwardFlow = sgRule.isEgress();
        cond.tpDst = sgRule.portRange();

        if (sgRule.isIngress()) {
            cond.nwSrcIp = sgRule.remoteIpv4Subnet();
            cond.ipAddrGroupIdSrc = sgRule.remoteGroupId;
        } else {
            cond.nwDstIp = sgRule.remoteIpv4Subnet();
            cond.ipAddrGroupIdDst = sgRule.remoteGroupId;
        }

        Rule cfg = new LiteralRule(cond, RuleResult.Action.ACCEPT);
        cfg.chainId = chainId;

        return cfg;
    }

    public static Rule createAcceptReturnFlowRule(UUID chainId) {
        Condition cond = new Condition();
        cond.matchReturnFlow = true;
        Rule cfg = new LiteralRule(cond, RuleResult.Action.ACCEPT);
        cfg.chainId = chainId;
        return cfg;
    }

    public static Rule createIpSpoofProtectionRule(UUID chainId, Subnet subnet,
                                                   String ipAddr) {
        Condition cond = new Condition();

        if (subnet.ipVersion == 6) {
            cond.nwSrcIp = new IPv6Subnet(
                    IPv6Addr.fromString(ipAddr), 128);
            cond.nwSrcInv = true;
            cond.dlType = (int) IPv6.ETHERTYPE;
        } else {
            cond.nwSrcIp = new IPv4Subnet(
                    IPv4Addr.fromString(ipAddr), 32);
            cond.nwSrcInv = true;
            cond.dlType = (int) IPv4.ETHERTYPE;
        }
        Rule cfg = new LiteralRule(cond, RuleResult.Action.DROP);
        cfg.chainId = chainId;

        return cfg;
    }

    public static Rule createMacSpoofProtectionRule(UUID chainId,
                                                    String macAddress) {
        // MAC spoofing protection for in_chain
        Condition cond = new Condition();
        cond.dlSrc = MAC.fromString(macAddress);
        cond.invDlSrc = true;
        Rule cfg = new LiteralRule(cond, RuleResult.Action.DROP);
        cfg.chainId = chainId;
        return cfg;
    }

    public static Rule createJumpRule(UUID chainId, UUID jumpChainId,
                                      String jumpChainName)
            throws SerializationException, StateAccessException {
        Rule r = new JumpRule(new Condition(), jumpChainId, jumpChainName);
        r.action = RuleResult.Action.JUMP;
        r.chainId = chainId;
        return r;
    }

    public static Rule createDropAllRule(UUID chainId) {

        Condition cond = new Condition();
        cond.dlType = (int) ARP.ETHERTYPE;  // ARP is ok
        cond.invDlType = true;
        Rule cfg = new LiteralRule(cond, RuleResult.Action.DROP);
        cfg.chainId = chainId;
        return cfg;
    }

    public static IpAddrGroupZkManager.IpAddrGroupConfig createIpAddrGroup(
            SecurityGroup sg) {

        IpAddrGroupZkManager.IpAddrGroupConfig config =
                new IpAddrGroupZkManager.IpAddrGroupConfig();
        config.id = sg.id;
        config.name = sg.name;

        return config;
    }
}
