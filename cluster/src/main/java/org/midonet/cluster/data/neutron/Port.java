/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.annotate.JsonTypeInfo;
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
    public boolean equals(Object obj) {

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
    public boolean isDhcp() {
        return deviceOwner == DeviceOwner.DHCP;
    }

    @JsonIgnore
    public boolean isRouterInterface() {
        return deviceOwner == DeviceOwner.ROUTER_INTF;
    }

    @JsonIgnore
    public boolean isRouterGateway() {
        return deviceOwner == DeviceOwner.ROUTER_GW;
    }

    @JsonIgnore
    public boolean isFloatingIp() {
        return deviceOwner == DeviceOwner.FLOATINGIP;
    }

    @JsonIgnore
    public boolean isVif() {
        return !(isDhcp() || isFloatingIp() || isRouterInterface()
                || isRouterGateway());
    }

}
