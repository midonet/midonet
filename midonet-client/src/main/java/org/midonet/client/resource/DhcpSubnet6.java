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
package org.midonet.client.resource;

import java.net.URI;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoDhcpSubnet6;
import org.midonet.client.dto.DtoDhcpV6Host;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCPV6_HOST_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCPV6_SUBNET_JSON;

/*
 * The reason the number 6 is at the end is to make the user think of
 * dhcp.conf for linux, where v4 configurations use the "subnet" option
 * and v6 configurations use "subnet6"
 */
public class DhcpSubnet6 extends ResourceBase<DhcpSubnet6, DtoDhcpSubnet6> {

    public DhcpSubnet6(WebResource resource, URI uriForCreation, DtoDhcpSubnet6
        principalDto) {
        super(resource, uriForCreation, principalDto,
              APPLICATION_DHCPV6_SUBNET_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getPrefix() {
        return principalDto.getPrefix();
    }

    public int getPrefixLength() {
        return principalDto.getPrefixLength();
    }

    public DhcpSubnet6 hosts(URI hosts) {
        principalDto.setHosts(hosts);
        return this;
    }

    public DhcpSubnet6 prefixLength(int length) {
        principalDto.setPrefixLength(length);
        return this;
    }

    public DhcpSubnet6 prefix(String prefix) {
        principalDto.setPrefix(prefix);
        return this;
    }

    public ResourceCollection<DhcpV6Host> getDhcpV6Hosts() {
        return getChildResources(
            principalDto.getHosts(),
            null,
            APPLICATION_DHCPV6_HOST_COLLECTION_JSON(),
            DhcpV6Host.class,
            DtoDhcpV6Host.class);
    }

    public DhcpV6Host addDhcpV6Host() {
        return new DhcpV6Host(resource, principalDto.getHosts(),
                              new DtoDhcpV6Host());
    }

    @Override
    public String toString() {
        return String.format("{DhcpSubnet6, prefix=%s, length=%s}",
                             principalDto.getPrefix(),
                             principalDto.getPrefixLength());
    }
}
