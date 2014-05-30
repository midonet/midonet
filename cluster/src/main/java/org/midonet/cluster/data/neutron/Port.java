/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.collection.ListUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Port {

    public UUID id;

    public String name;

    @JsonProperty("network_id")
    public UUID networkId;

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("mac_address")
    public String macAddress;

    @JsonProperty("fixed_ips")
    public List<IPAllocation> fixedIps = new ArrayList<>();

    @JsonProperty("device_id")
    public String deviceId;

    @JsonProperty("device_owner")
    public DeviceOwner deviceOwner;

    @JsonProperty("tenant_id")
    public String tenantId;

    public String status;

    @JsonProperty("security_groups")
    public List<UUID> securityGroups = new ArrayList<>();

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Port)) return false;
        final Port other = (Port) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(name, other.name)
                && Objects.equal(networkId, other.networkId)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(macAddress, other.macAddress)
                && Objects.equal(deviceId, other.deviceId)
                && Objects.equal(deviceOwner, other.deviceOwner)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(status, other.status)
                && ListUtils.isEqualList(fixedIps, other.fixedIps)
                && ListUtils.isEqualList(securityGroups, other.securityGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, networkId, adminStateUp, macAddress,
                deviceId, deviceOwner, tenantId, status,
                ListUtils.hashCodeForList(fixedIps),
                ListUtils.hashCodeForList(securityGroups));
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("networkId", networkId)
                .add("adminStateUp", adminStateUp)
                .add("macAddress", macAddress)
                .add("deviceId", deviceId)
                .add("deviceOwner", deviceOwner)
                .add("tenantId", tenantId)
                .add("status", status)
                .add("fixedIps", ListUtil.toString(fixedIps))
                .add("securityGroups", ListUtil.toString(securityGroups))
                .toString();
    }

    @JsonIgnore
    public final boolean hasIp() {
        return fixedIps != null && fixedIps.size()> 0;
    }

    @JsonIgnore
    public final IPv4Addr firstIpv4Addr() {
        if (!hasIp()) return null;

        String addr = fixedIps.get(0).ipAddress;
        return IPv4Addr.fromString(addr);
    }

    @JsonIgnore
    public final IPv4Subnet firstIpv4Subnet() {
        if (!hasIp()) return null;
        return new IPv4Subnet(firstIpv4Addr(), 32);
    }

    @JsonIgnore
    public final MAC macAddress() {
        if (macAddress == null) return null;
        return MAC.fromString(macAddress);
    }

    @JsonIgnore
    public final boolean isDhcp() {
        return deviceOwner == DeviceOwner.DHCP;
    }

    @JsonIgnore
    public final boolean isDhcp(UUID netId) {
        return isDhcp() && Objects.equal(networkId, netId);
    }

    @JsonIgnore
    public final boolean isRouterInterface() {
        return deviceOwner == DeviceOwner.ROUTER_INTF;
    }

    @JsonIgnore
    public final boolean isRouterGateway() {
        return deviceOwner == DeviceOwner.ROUTER_GW;
    }

    @JsonIgnore
    public final boolean isFloatingIp() {
        return deviceOwner == DeviceOwner.FLOATINGIP;
    }

    @JsonIgnore
    public final boolean isVif() {
        return !(isDhcp() || isFloatingIp() || isRouterInterface()
                || isRouterGateway());
    }

    @JsonIgnore
    public final String egressChainName() {
        if (id == null) return null;
        return egressChainName(id);
    }

    @JsonIgnore
    public final String ingressChainName() {
        if (id == null) return null;
        return ingressChainName(id);
    }

    @JsonIgnore
    public final UUID deviceIdUuid() {
        if (deviceId == null) return null;
        return UUID.fromString(deviceId);
    }

    public static String egressChainName(UUID portId) {
        if (portId == null)
            throw new IllegalArgumentException("portId is null");

        return "OS_PORT_" + portId + "_INBOUND";
    }

    public static String ingressChainName(UUID portId) {
        if (portId == null)
            throw new IllegalArgumentException("portId is null");

        return "OS_PORT_" + portId + "_OUTBOUND";
    }
}
