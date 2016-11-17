/*
 * Copyright 2014 Midokura SARL
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
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv6;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.IPv6Subnet;

@ZoomClass(clazz = Topology.DhcpV6.class)
public class DhcpSubnet6 extends UriResource {

    @JsonIgnore
    @ZoomField(name = "id")
    public UUID id;

    @NotNull
    @Pattern(regexp = IPv6.regex, message = "is an invalid IPv6 format")
    public String prefix;

    @Min(0)
    @Max(128)
    public int prefixLength;

    @JsonIgnore
    @ZoomField(name = "network_id")
    public UUID bridgeId;

    @JsonIgnore
    @ZoomField(name = "subnet_address",
               converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> subnetAddress;

    @JsonIgnore
    @ZoomField(name = "hosts")
    public List<DhcpV6Host> dhcpHosts;

    /* Default constructor is needed for parsing/unparsing. */
    public DhcpSubnet6() { }

    public URI getHosts() {
        return UriBuilder.fromUri(getUri())
                         .path(ResourceUris.DHCPV6_HOSTS())
                         .build();
    }

    public URI getUri() {
        IPv6Subnet subnetAddr = new IPv6Subnet(IPv6Addr.fromString(prefix),
                                               prefixLength);
        return absoluteUri(ResourceUris.BRIDGES(), bridgeId,
                           ResourceUris.DHCPV6(), subnetAddr.toZkString());
    }

    @JsonIgnore
    public void create(UUID bridgeId) {
        if (null == id) {
            id = UUID.randomUUID();
        }
        this.bridgeId = bridgeId;
    }

    @JsonIgnore
    public void update(DhcpSubnet6 from) {
        id = from.id;
        subnetAddress = from.subnetAddress;
        bridgeId = from.bridgeId;
        dhcpHosts = from.dhcpHosts;
    }

    @JsonIgnore
    @Override
    public void afterFromProto(Message proto) {
        prefixLength = subnetAddress.getPrefixLen();
        prefix = subnetAddress.getAddress().toString();
    }

    @JsonIgnore
    @Override
    public void beforeToProto() {
        subnetAddress = IPSubnet.fromString(prefix, prefixLength);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("prefix", prefix)
            .add("prefixLength", prefixLength)
            .add("bridgeId", bridgeId)
            .add("subnetAddress", subnetAddress)
            .add("dhcpHosts", dhcpHosts)
            .toString();
    }
}
