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

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.*;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.collection.ListUtil;

import static org.apache.commons.collections4.ListUtils.hashCodeForList;
import static org.apache.commons.collections4.ListUtils.isEqualList;

@ZoomClass(clazz = Neutron.GatewayDevice.class)
public class GatewayDevice extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "type")
    public GatewayType type;

    @JsonProperty("resource_id")
    @ZoomField(name = "resource_id")
    public UUID resourceId;

    @JsonProperty("tunnel_ips")
    @ZoomField(name = "tunnel_ips")
    public List<IPv4Addr> tunnelIps;

    @JsonProperty("management_ip")
    @ZoomField(name = "management_ip")
    public String managementIp;

    @JsonProperty("management_port")
    @ZoomField(name = "management_port")
    public Integer managementPort;

    @JsonProperty("management_protocol")
    @ZoomField(name = "management_protocol")
    public GatewayDeviceProtocol managementProtocol;

    @JsonProperty("remote_mac_entries")
    @ZoomField(name = "remote_mac_entries")
    public List<RemoteMacEntry> remoteMacEntries;

    @ZoomEnum(clazz = Neutron.GatewayDevice.GatewayType.class)
    public enum GatewayType {
        @ZoomEnumValue("ROUTER_VTEP") ROUTER_VTEP,
        @ZoomEnumValue("HW_VTEP") HW_VTEP;
    }

    @ZoomEnum(clazz = Neutron.GatewayDevice.GatewayDeviceProtocol.class)
    public enum GatewayDeviceProtocol {
        @ZoomEnumValue("OVSDB") OVSDB;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GatewayDevice that = (GatewayDevice) o;

        return Objects.equal(id, that.id) &&
               Objects.equal(type, that.type) &&
               Objects.equal(resourceId, that.resourceId) &&
               isEqualList(tunnelIps, that.tunnelIps) &&
               Objects.equal(managementIp, that.managementIp) &&
               Objects.equal(managementPort, that.managementPort) &&
               Objects.equal(managementProtocol, that.managementProtocol) &&
               isEqualList(remoteMacEntries, that.remoteMacEntries);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, type, resourceId,
                                hashCodeForList(tunnelIps), managementIp,
                                managementPort, managementProtocol,
                                hashCodeForList(remoteMacEntries));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("type", type)
            .add("resourceId", resourceId)
            .add("tunnelIps", ListUtil.toString(tunnelIps))
            .add("managementIp", managementIp)
            .add("managementPort", managementPort)
            .add("managementProtocol", managementProtocol)
            .add("remoteMacEntries", ListUtil.toString(remoteMacEntries))
            .toString();
    }
}
