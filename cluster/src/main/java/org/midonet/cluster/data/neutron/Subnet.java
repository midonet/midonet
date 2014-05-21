/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.packets.*;
import org.midonet.util.collection.ListUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Subnet {

    public UUID id;
    public String name;

    @JsonProperty("ip_version")
    public Integer ipVersion;

    @JsonProperty("network_id")
    public UUID networkId;

    public String cidr;

    @JsonProperty("gateway_ip")
    public String gatewayIp;

    @JsonProperty("allocation_pools")
    public List<IPAllocationPool> allocationPools = new ArrayList<>();

    @JsonProperty("dns_nameservers")
    public List<String> dnsNameservers = new ArrayList<>();

    @JsonProperty("host_routes")
    public List<Route> hostRoutes = new ArrayList<>();

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("enable_dhcp")
    public boolean enableDhcp;

    @JsonProperty("shared")
    public boolean shared;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Subnet)) return false;
        final Subnet other = (Subnet) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(name, other.name)
                && Objects.equal(ipVersion, other.ipVersion)
                && Objects.equal(networkId, other.networkId)
                && Objects.equal(cidr, other.cidr)
                && Objects.equal(gatewayIp, other.gatewayIp)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(enableDhcp, other.enableDhcp)
                && Objects.equal(shared, other.shared)
                && ListUtils.isEqualList(
                        allocationPools, other.allocationPools)
                && ListUtils.isEqualList(
                        dnsNameservers, other.dnsNameservers)
                && ListUtils.isEqualList(
                        hostRoutes, other.hostRoutes);
    }

    @Override
    public int hashCode() {

        return Objects.hashCode(id, name, ipVersion, networkId, cidr,
                gatewayIp, tenantId, enableDhcp, shared,
                ListUtils.hashCodeForList(allocationPools),
                ListUtils.hashCodeForList(dnsNameservers),
                ListUtils.hashCodeForList(hostRoutes));
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("ipVersion", ipVersion)
                .add("networkId", networkId)
                .add("cidr", cidr)
                .add("gatewayIp", gatewayIp)
                .add("tenantId", tenantId)
                .add("enableDhcp", enableDhcp)
                .add("shared", shared)
                .add("allocationPools",
                        ListUtil.toString(allocationPools))
                .add("dnsNameservers",
                        ListUtil.toString(dnsNameservers))
                .add("hostRoutes",
                        ListUtil.toString(hostRoutes))
                .toString();
    }

    @JsonIgnore
    public boolean isIpv4() {
        return ipVersion == 4;
    }

    @JsonIgnore
    public IPv4Subnet ipv4Subnet() {
        if (cidr == null) return null;

        if (isIpv4()) {
            return new IPv4Subnet(cidr);
        } else {
            // TODO support IPv6
            return null;
        }
    }

    @JsonIgnore
    public int cidrAddressInt() {
        IPv4Subnet ipSubnet = ipv4Subnet();
        if (ipSubnet == null) return 0;

        return ipSubnet.getIntAddress();
    }

    @JsonIgnore
    public int cidrAddressLen() {
        IPv4Subnet ipSubnet = ipv4Subnet();
        if (ipSubnet == null) return 0;

        return ipSubnet.getPrefixLen();
    }

    @JsonIgnore
    public int gwIpInt() {
        if (gatewayIp == null) return 0;
        return IPv4Addr.stringToInt(gatewayIp);
    }
}