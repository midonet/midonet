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

import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.ParentId;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;

@XmlRootElement
@ZoomClass(clazz = Topology.Dhcp.class)
@Resource(name = ResourceUris.DHCP, parents = { Bridge.class })
public class DhcpSubnet extends UriResource {

    @ResourceId
    @ZoomField(name = "id", converter = IdConverter.class)
    public String id;

    @ParentId
    @ZoomField(name = "network_id", converter = UUIDUtil.Converter.class)
    public UUID networkId;

    @XmlTransient
    @ZoomField(name = "subnet_address",
               converter = IPSubnetUtil.Converter.class)
    public String subnetAddress;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    public String subnetPrefix;

    @Min(0)
    @Max(32)
    public int subnetLength;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "default_gateway",
               converter = IPAddressUtil.Converter.class)
    public String defaultGateway;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "server_address",
               converter = IPAddressUtil.Converter.class)
    public String serverAddr;

    @ZoomField(name = "dns_server_address",
               converter = IPAddressUtil.Converter.class)
    public List<String> dnsServerAddrs;

    @Min(0)
    @Max(65536)
    @ZoomField(name = "interface_mtu")
    public int interfaceMTU;

    @ZoomField(name = "opt121_routes")
    public List<DhcpOption121> opt121Routes;

    @XmlTransient
    @Subresource(name = ResourceUris.DHCP_HOSTS)
    @ZoomField(name = "hosts")
    public List<DhcpHost> hosts;

    @ZoomField(name = "enabled")
    public Boolean enabled = true;

    public URI getHosts() {
        return relativeUri(ResourceUris.HOSTS);
    }

    @Override
    public void afterFromProto(Message proto) {
        IPSubnet<?> subnet = IPSubnet.fromString(subnetAddress);
        subnetPrefix = subnet.getAddress().toString();
        subnetLength = subnet.getPrefixLen();
    }

    @Override
    public void beforeToProto() {
        id = subnetPrefix + "_" + subnetLength;
        subnetAddress = subnetPrefix + "/" + subnetLength;
    }

    public static class IdConverter
        extends ZoomConvert.Converter<String, Commons.UUID> {
        @Override
        public Commons.UUID toProto(String value, Type clazz) {
            String[] tokens = value.split("_");
            IPv4Addr prefix= IPv4Addr.apply(tokens[0]);
            int length = Integer.parseInt(tokens[1]);
            return Commons.UUID.newBuilder()
                               .setMsb(prefix.addr()).setLsb(length).build();
        }
        @Override
        public String fromProto(Commons.UUID value, Type clazz) {
            IPv4Addr prefix = IPv4Addr.apply((int)value.getMsb());
            return prefix.toString() + "_" + value.getLsb();
        }
    }
}
