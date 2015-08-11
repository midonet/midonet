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

package org.midonet.cluster.rest_api.models.federation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;

@ZoomClass(clazz = Federation.OvsdbRouterBinding.class)
public class OvsdbBinding extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "vtep_id", converter = Converter.class)
    public UUID vtepId;

    @NotNull
    @ZoomField(name = "segment_id", converter = Converter.class)
    public UUID segmentId;

    @NotNull
    @ZoomField(name = "port_name")
    public String portName;

    @Min(0)
    @Max(4095)
    @ZoomField(name = "vlan_id")
    public short vlanId;

    @Pattern(regexp = MAC.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "router_mac")
    public String routerMac;

    @NotNull
    @Pattern(regexp = IPv4Subnet.IPV4_CIDR_PATTERN, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "router_cidr", converter = IPSubnetUtil.Converter.class)
    public String routerCidr;

    @NotNull
    //@Pattern(regexp = IPv4Subnet.IPV4_CIDR_PATTERN, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "local_subnets", converter = IPSubnetUtil.Converter.class)
    public List<String> localSubnets;

    @Override
    public URI getUri() {
        return absoluteUri(Application.OVSDB_BINDINGS, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(OvsdbBinding from) {
        this.id = from.id;
    }
}
