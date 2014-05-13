/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.packets.IntIPv4;

import java.util.ArrayList;
import java.util.List;

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

}
