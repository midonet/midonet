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
package org.midonet.cluster.rest_api.neutron.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.collection.ListUtil;

import static org.apache.commons.collections4.ListUtils.hashCodeForList;
import static org.apache.commons.collections4.ListUtils.isEqualList;

@ZoomClass(clazz = Neutron.NeutronPort.class)
public class Port extends ZoomObject {

    public Port() {}

    public Port(UUID id, UUID netId, String tenantId, String name,
                String macAddress, List<IPAllocation> fixedIps,
                DeviceOwner deviceOwner, String deviceId,
                List<UUID> sgIds) {
        this.id = id;
        this.networkId = netId;
        this.tenantId = tenantId;
        this.name = name;
        this.macAddress = macAddress;
        this.adminStateUp = true;
        this.fixedIps = fixedIps;
        this.deviceOwner = deviceOwner;
        this.deviceId = deviceId;
        this.securityGroups = sgIds;
    }

    public Port(UUID id, UUID netId, String tenantId, String name,
                String macAddress, List<IPAllocation> fixedIps,
                DeviceOwner deviceOwner, String deviceId,
                List<UUID> sgIds, List<ExtraDhcpOpt> extraDhcpOpts) {
        this(id, netId, tenantId, name, macAddress, fixedIps, deviceOwner,
                deviceId, sgIds);
        this.extraDhcpOpts = extraDhcpOpts;
    }

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("network_id")
    @ZoomField(name = "network_id")
    public UUID networkId;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("mac_address")
    @ZoomField(name = "mac_address")
    public String macAddress;

    @JsonProperty("fixed_ips")
    @ZoomField(name = "fixed_ips")
    public List<IPAllocation> fixedIps = new ArrayList<>();

    @JsonProperty("device_id")
    @ZoomField(name = "device_id")
    public String deviceId;

    @JsonProperty("device_owner")
    @ZoomField(name = "device_owner")
    public DeviceOwner deviceOwner;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "status")
    public String status;

    @JsonProperty("security_groups")
    @ZoomField(name = "security_groups")
    public List<UUID> securityGroups = new ArrayList<>();

    @JsonProperty("binding:host_id")
    @ZoomField(name = "host_id")
    public String hostId;

    @JsonProperty("binding:profile")
    @ZoomField(name = "profile")
    public PortBindingProfile bindingProfile;

    @JsonProperty("port_security_enabled")
    @ZoomField(name = "port_security_enabled")
    public boolean securityEnabled = true;

    @JsonProperty("allowed_address_pairs")
    @ZoomField(name = "allowed_address_pairs")
    public List<PortAllowedAddressPair> allowedAddrPairs;

    @JsonProperty("extra_dhcp_opts")
    @ZoomField(name = "extra_dhcp_opts")
    public List<ExtraDhcpOpt> extraDhcpOpts = new ArrayList<>();

    @JsonProperty("qos_policy_id")
    @ZoomField(name = "qos_policy_id")
    public UUID qosPolicyId;

    @Override
    public final boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof Port)) return false;

        final Port other = (Port) obj;
        return Objects.equal(id, other.id)
                && Objects.equal(name, other.name)
                && Objects.equal(networkId, other.networkId)
                && adminStateUp == other.adminStateUp
                && Objects.equal(macAddress, other.macAddress)
                && Objects.equal(deviceId, other.deviceId)
                && deviceOwner == other.deviceOwner
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(status, other.status)
                && isEqualList(fixedIps, other.fixedIps)
                && isEqualList(securityGroups, other.securityGroups)
                && Objects.equal(hostId, other.hostId)
                && Objects.equal(bindingProfile, other.bindingProfile)
                && securityEnabled == other.securityEnabled
                && isEqualList(allowedAddrPairs, other.allowedAddrPairs)
                && isEqualList(extraDhcpOpts, other.extraDhcpOpts)
                && Objects.equal(qosPolicyId, other.qosPolicyId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(
            id, name, networkId, adminStateUp, macAddress, deviceId,
            deviceOwner, tenantId, status, hashCodeForList(fixedIps),
            hashCodeForList(securityGroups), hashCodeForList(extraDhcpOpts),
            hostId, bindingProfile, securityEnabled,
            hashCodeForList(allowedAddrPairs), hashCodeForList(extraDhcpOpts),
            qosPolicyId);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("networkId", networkId)
                .add("adminStateUp", adminStateUp)
                .add("macAddress", macAddress)
                .add("deviceId", deviceId)
                .add("deviceOwner", deviceOwner)
                .add("tenantId", tenantId)
                .add("hostId", hostId)
                .add("bindingProfile", bindingProfile)
                .add("securityEnabled", securityEnabled)
                .add("status", status)
                .add("fixedIps", ListUtil.toString(fixedIps))
                .add("securityGroups", ListUtil.toString(securityGroups))
                .add("dhcpExtraOpts", ListUtil.toString(extraDhcpOpts))
                .add("allowedAddrPairs", ListUtil.toString(allowedAddrPairs))
                .add("qosPolicyId", qosPolicyId)
                .toString();
    }
}
