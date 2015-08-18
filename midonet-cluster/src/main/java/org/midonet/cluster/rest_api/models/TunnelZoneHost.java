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

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.TunnelZone.HostToIp.class)
public class TunnelZoneHost extends UriResource {

    public UUID tunnelZoneId;

    @ZoomField(name = "host_id", converter = UUIDUtil.Converter.class)
    public UUID hostId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "ip", converter = IPAddressUtil.Converter.class)
    public String ipAddress;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.TUNNEL_ZONES, tunnelZoneId,
                           ResourceUris.HOSTS, hostId);
    }

    public URI getHost() {
        return absoluteUri(ResourceUris.HOSTS, hostId);
    }

    public URI getTunnelZone() {
        return absoluteUri(ResourceUris.TUNNEL_ZONES, tunnelZoneId);
    }

    @JsonIgnore
    public void create(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("tunnelZoneId", tunnelZoneId)
            .add("hostId", hostId)
            .add("ipAddress", ipAddress)
            .toString();
    }
}
