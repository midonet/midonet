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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.packets.IPv4;

@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "version")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Ipv4AddrGroupAddr.class, name = "4"),
    @JsonSubTypes.Type(value = Ipv6AddrGroupAddr.class, name = "6")
})
public abstract class IpAddrGroupAddr extends UriResource {

    // IP address in canonical form. Can be either IPv4 or IPv6.
    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is not a valid IP address")
    protected String addr;

    @NotNull
    public UUID ipAddrGroupId;

    public IpAddrGroupAddr() { }

    public IpAddrGroupAddr(UUID ipAddrGroupId, String addr) {
        this.addr = addr;
        this.ipAddrGroupId = ipAddrGroupId;
    }

    public String getAddr() {
        return addr;
    }

    public abstract int getVersion();

    @Override
    public URI getUri() {
        try {
            URI ipAddrGroupUri = getIpAddrGroup();
            return UriBuilder.fromUri(ipAddrGroupUri)
                             .path(ResourceUris.VERSIONS)
                             .path(Integer.toString(getVersion()))
                             .path(ResourceUris.IP_ADDRS)
                             .path(URLEncoder.encode(addr, "UTF-8"))
                             .build();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public URI getIpAddrGroup() {
        return absoluteUri(ResourceUris.IP_ADDR_GROUPS, ipAddrGroupId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("addr", addr)
            .add("ipAddrGroupId", ipAddrGroupId)
            .toString();
    }
}
