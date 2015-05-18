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
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.packets.IPv6;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.IPv6Subnet;

@XmlRootElement
public class DhcpSubnet6 extends UriResource {

    @NotNull
    @Pattern(regexp = IPv6.regex, message = "is an invalid IP format")
    public String prefix;

    @Min(0)
    @Max(128)
    public int prefixLength;

    @JsonIgnore
    public UUID bridgeId;

    /* Default constructor is needed for parsing/unparsing. */
    public DhcpSubnet6() { }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
    }

    public URI getHosts() {
        return UriBuilder.fromUri(getUri())
                         .path(ResourceUris.DHCPV6_HOSTS)
                         .build();
    }

    public URI getUri() {
        IPv6Subnet subnetAddr = new IPv6Subnet(IPv6Addr.fromString(prefix),
                                               prefixLength);
        return absoluteUri(ResourceUris.BRIDGES, bridgeId,
                           ResourceUris.DHCPV6, subnetAddr.toZkString());
    }

}
