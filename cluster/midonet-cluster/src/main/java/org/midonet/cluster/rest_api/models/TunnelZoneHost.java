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

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.TunnelZone.HostToIp.class)
public class TunnelZoneHost extends UriResource {

    private UUID tunnelZoneId;

    @ZoomField(name = "host_id", converter = UUIDUtil.Converter.class)
    private UUID hostId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "ip", converter = IPAddressUtil.Converter.class)
    public String ipAddress;

    public UUID getTunnelZoneId() {
        return this.tunnelZoneId;
    }

    public void setTunnelZoneId(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    public UUID getHostId() {
        return this.hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public String getIpAddress() {
        return this.ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.TUNNEL_ZONES, tunnelZoneId,
                           ResourceUris.HOSTS, hostId);
    }

    @JsonIgnore
    public void create(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    public static class TunnelZoneHostData extends TunnelZoneHost {

        private URI uri;

        @Override
        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

    }
}
