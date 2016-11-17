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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.Dhcp.class)
public class DhcpSubnet extends UriResource {

    @JsonIgnore
    @ZoomField(name = "id")
    public UUID id;

    @JsonIgnore
    @ZoomField(name = "network_id")
    public UUID bridgeId;

    @JsonIgnore
    @ZoomField(name = "subnet_address",
               converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> subnetAddress;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IPv4 format")
    public String subnetPrefix;

    @Min(0)
    @Max(32)
    public int subnetLength;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IPv4 format")
    @ZoomField(name = "default_gateway",
               converter = IPAddressUtil.Converter.class)
    public String defaultGateway;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IPv4 format")
    @ZoomField(name = "server_address",
               converter = IPAddressUtil.Converter.class)
    public String serverAddr;

    @ZoomField(name = "dns_server_address",
               converter = IPAddressUtil.Converter.class)
    public List<String> dnsServerAddrs;

    @Min(0)
    @Max(65536)
    @ZoomField(name = "interface_mtu")
    public Integer interfaceMTU;

    @ZoomField(name = "opt121_routes")
    public List<DhcpOption121> opt121Routes;

    @JsonIgnore
    @ZoomField(name = "hosts")
    public List<DhcpHost> dhcpHosts;

    @ZoomField(name = "enabled")
    public Boolean enabled = true;

    public DhcpSubnet() {
        opt121Routes = new ArrayList<>();
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BRIDGES(), bridgeId,
                           ResourceUris.DHCP(), subnetAddress.toUriString());
    }

    public URI getHosts() {
        return UriBuilder.fromUri(getUri()).path(ResourceUris.HOSTS()).build();
    }

    @JsonIgnore
    @Override
    public void afterFromProto(Message proto) {
        subnetPrefix = subnetAddress.getAddress().toString();
        subnetLength = subnetAddress.getPrefixLen();
        if (dnsServerAddrs != null && dnsServerAddrs.isEmpty()) {
            dnsServerAddrs = null;
        }
    }

    @JsonIgnore
    public void create(UUID bridgeId) {
        if (null == id) {
            id = UUID.randomUUID();
        }
        this.bridgeId = bridgeId;

        try {
            subnetAddress = IPSubnet.fromString(subnetPrefix, subnetLength);
        } catch (IllegalArgumentException ex){
            throw new BadRequestHttpException(ex, ex.getMessage());
        }
    }

    @JsonIgnore
    public void update(DhcpSubnet from) {
        id = from.id;
        subnetAddress = IPSubnet.fromString(subnetPrefix, subnetLength);
        bridgeId = from.bridgeId;
        dhcpHosts = from.dhcpHosts;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("id", id)
            .add("bridgeId", bridgeId)
            .add("subnetAddress", subnetAddress)
            .add("subnetPrefix", subnetPrefix)
            .add("subnetLength", subnetLength)
            .add("defaultGateway", defaultGateway)
            .add("serverAddr", serverAddr)
            .add("dnsServerAddrs", dnsServerAddrs)
            .add("interfaceMTU", interfaceMTU)
            .add("opt121Routes", opt121Routes)
            .add("dhcpHosts", dhcpHosts)
            .add("enabled", enabled)
            .toString();
    }
}
