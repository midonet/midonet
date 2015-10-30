/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.rest_api;

import java.util.UUID;

import org.midonet.packets.MAC;

/**
 * This class contains the name of all the resources that are exposed via our
 * REST API.
 */
public final class ResourceUris {
    public static final String ARP_TABLE = "arp_table";
    public static final String BGP_NETWORKS = "bgp_networks";
    public static final String BGP_PEERS = "bgp_peers";
    public static final String BINDINGS = "bindings";
    public static final String BRIDGES = "bridges";
    public static final String CHAINS = "chains";
    public static final String DHCP = "dhcp";
    public static final String DHCPV6 = "dhcpV6";
    public static final String DHCPV6_HOSTS = "hostsV6";
    public static final String DHCP_HOSTS = "hosts";
    public static final String HEALTH_MONITORS = "health_monitors";
    public static final String HOSTS = "hosts";
    public static final String INTERFACES = "interfaces";
    public static final String IP_ADDRS = "ip_addrs";
    public static final String IP_ADDR_GROUPS = "ip_addr_groups";
    public static final String LINK = "link";
    public static final String LOAD_BALANCERS = "load_balancers";
    public static final String L2INSERTIONS = "l2insertions";
    public static final String L2SERVICES = "l2services";
    public static final String MAC_TABLE = "mac_table";
    public static final String MIRRORS = "mirrors";
    public static final String PEER_PORTS = "peer_ports";
    public static final String POOLS = "pools";
    public static final String POOL_MEMBERS = "pool_members";
    public static final String PORTS = "ports";
    public static final String PORT_GROUPS = "port_groups";
    public static final String ROUTERS = "routers";
    public static final String ROUTES = "routes";
    public static final String RULES = "rules";
    public static final String SYSTEM_STATE = "system_state";
    public static final String TENANTS = "tenants";
    public static final String TRACE_REQUESTS = "traces";
    public static final String TUNNEL_ZONES = "tunnel_zones";
    public static final String VERSIONS = "versions";
    public static final String VIPS = "vips";
    public static final String VLANS = "vlans";
    public static final String VTEPS = "vteps";
    public static final String VTEP_BINDINGS = "vtep_bindings";
    public static final String VXLAN_PORTS = "vxlan_ports";
    public static final String WRITE_VERSION = "write_version";

    // TODO: remove these? they are templates, and belong in the resources IMO
    public static final String TENANT_ID_PARAM = "tenant_id";

    public static String macToUri(String mac) {
        return mac.replace(':', '-');
    }

    public static MAC macPortUriToMac(String macPortAsUri) {
        return MAC.fromString(macPortAsUri.split("_")[0].replace('-', ':'));
    }

    public static UUID macPortUriToPort(String macPortAsUri) {
        return UUID.fromString(macPortAsUri.split("_")[1]);
    }

}
