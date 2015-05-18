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
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.packets.IPv6Subnet;

@XmlRootElement
public class DhcpV6Host extends UriResource {

    public String clientId;
    public String fixedAddress;
    public String name;

    @JsonIgnore
    public UUID bridgeId;

    @JsonIgnore
    public IPv6Subnet prefix;

    /* Default constructor - for deserialization. */
    public DhcpV6Host() {
    }

    @Override
    public URI getUri() {
        URI uri = absoluteUri(ResourceUris.BRIDGES, bridgeId,
                              ResourceUris.DHCPV6, prefix.toZkString());
        return UriBuilder.fromUri(uri)
                         .path(ResourceUris.DHCPV6_HOSTS)
                         .path(clientIdToUri(clientId))
                         .build();
    }

    public static String clientIdToUri(String clientId) {
        return clientId.replace(':', '-');
    }

    public static String clientIdFromUri(String clientId) {
        return clientId.replace('-', ':');
    }


}

