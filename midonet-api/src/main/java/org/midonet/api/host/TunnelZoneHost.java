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
package org.midonet.api.host;

import java.net.URI;
import java.util.UUID;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.host.TunnelZoneHost.TunnelZoneHostUnique;
import org.midonet.api.host.validation.IsUniqueTunnelZoneMember;
import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.UriResource;
import org.midonet.brain.services.rest_api.host.validation.IsValidHostId;
import org.midonet.brain.services.rest_api.host.validation.IsValidTunnelZoneId;
import org.midonet.cluster.data.TunnelZone.HostConfig;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;

@IsUniqueTunnelZoneMember(groups = TunnelZoneHostUnique.class)
@XmlRootElement
public class TunnelZoneHost extends UriResource {

    @IsValidTunnelZoneId
    private UUID tunnelZoneId;

    @IsValidHostId
    private UUID hostId;

    @NotNull
    @Pattern(regexp = IPv4.regex,
            message = "is an invalid IP format")
    private String ipAddress;

    public TunnelZoneHost() {
    }

    public TunnelZoneHost(UUID tunnelZoneId, HostConfig data) {
        this(tunnelZoneId, UUID.fromString(data.getId().toString()));
        this.ipAddress = data.getIp().toString();
    }

    public TunnelZoneHost(UUID tunnelZoneId, UUID hostId) {
        this.tunnelZoneId = tunnelZoneId;
        this.hostId = hostId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public HostConfig toData() {
        HostConfig data = new HostConfig(null, new HostConfig.Data());
        setData(data);
        return data;
    }

    public UUID getTunnelZoneId() {
        return tunnelZoneId;
    }

    public void setTunnelZoneId(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    protected void setData(HostConfig data) {
        data.setId(hostId);
        data.setIp(IPv4Addr.fromString(ipAddress));
    }

    @Override
    public URI getUri() {
        if (getBaseUri() != null && tunnelZoneId != null
                && hostId != null) {
            return ResourceUriBuilder.getTunnelZoneHost(getBaseUri(),
                    tunnelZoneId, hostId);
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        return "TunnelZoneHost{" +
                "tunnelZoneId=" + tunnelZoneId +
                ", hostId=" + hostId +
                ", ipAddress='" + ipAddress + '\'' +
                '}';
    }

    /**
     * Interface used for validating a tunnel zone on creates.
     */
    public interface TunnelZoneHostUnique {
    }

    /**
     * Interface that defines the ordering of validation groups for tunnel zone
     * create.
     */
    @GroupSequence({ Default.class, TunnelZoneHostUnique.class })
    public interface TunnelZoneHostCreateGroupSequence {
    }
}
