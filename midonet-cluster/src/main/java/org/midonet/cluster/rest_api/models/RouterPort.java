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
package org.midonet.cluster.rest_api.models;

import java.util.List;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;
import com.google.protobuf.Message;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;
import org.midonet.packets.MAC;

public class RouterPort extends Port {

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String networkAddress;

    @Min(0)
    @Max(32)
    public int networkLength;

    @JsonIgnore
    @ZoomField(name = "port_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> portSubnet;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "port_address", converter = IPAddressUtil.Converter.class)
    public String portAddress;

    @ZoomField(name = "port_mac")
    public String portMac;

    @Min(0)
    @Max(2^24 - 1)
    @ZoomField(name = "vni")
    public int rtrPortVni;

    @ZoomField(name = "off_ramp_vxlan")
    public boolean offRampVxlan;

    @ZoomField(name = "remote_vteps")
    public List<MacIp> remoteVteps;

    @JsonIgnore
    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @JsonIgnore
    @ZoomField(name = "route_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> routeIds;

    public String bgpStatus;

    @Override
    public UUID getDeviceId() {
        return routerId;
    }

    @Override
    public void setDeviceId(UUID deviceId) {
        routerId = deviceId;
    }

    @Override
    public void afterFromProto(Message message) {
        if (null != portSubnet) {
            networkAddress = portSubnet.getAddress().toString();
            networkLength = portSubnet.getPrefixLen();
        }
    }

    @Override
    public void beforeToProto() {
        if (StringUtils.isNotEmpty(networkAddress)) {
            portSubnet =
                IPSubnet.fromString(networkAddress + "/" + networkLength);
        }
    }

    public void create(UUID routerId) {
        super.create();
        this.routerId = routerId;
        if (null == portMac) {
            portMac = MAC.random().toString();
        }
    }

    @Override
    public void update(Port from) {
        super.update(from);
        RouterPort routerPort = (RouterPort)from;
        routerId = routerPort.routerId;
        routeIds = routerPort.routeIds;
    }

    @Override
    public String toString() {
        return toStringHelper()
            .add("networkAddress", networkAddress)
            .add("networkLength", networkLength)
            .add("portSubnet", portSubnet)
            .add("portAddress", portAddress)
            .add("portMac", portMac)
            .add("routerId", routerId)
            .add("routeIds", routeIds)
            .toString();
    }
}
