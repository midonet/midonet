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

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.host.validation.IsHostIdInAnyTunnelZone;
import org.midonet.api.host.validation.IsHostInterfaceUnused;
import org.midonet.api.host.validation.IsUniqueTunnelZoneMember;
import org.midonet.api.host.validation.IsValidHostId;
import org.midonet.api.network.validation.IsValidPortId;
import org.midonet.cluster.data.host.VirtualPortMapping;

import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Host interface - port map DTO
 */
@IsHostInterfaceUnused(groups = HostInterfacePort.HostInterfacePortCreateGroup.class)
@XmlRootElement
public class HostInterfacePort extends UriResource {

    @NotNull
    @IsHostIdInAnyTunnelZone(groups = HostInterfacePortCreateGroup.class)
    private UUID hostId;

    @IsValidPortId
    @NotNull
    private UUID portId;

    @NotNull(groups = HostInterfacePortCreateGroup.class)
    private String interfaceName;

    public HostInterfacePort(){
    }

    public HostInterfacePort(UUID hostId, String interfaceName,
                                UUID portId) {
        this.hostId = hostId;
        this.portId = portId;
        this.interfaceName = interfaceName;
    }

    public HostInterfacePort(UUID hostId, VirtualPortMapping mapping) {
        this(hostId, mapping.getLocalDeviceName(), mapping.getVirtualPortId());
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public VirtualPortMapping toData() {
        return new VirtualPortMapping()
                .setLocalDeviceName(this.interfaceName)
                .setVirtualPortId(this.portId)
                .setId(this.portId);
    }

    public URI getUri() {
        if (getBaseUri() != null && hostId != null && portId != null) {
            return ResourceUriBuilder.getHostInterfacePort(
                    getBaseUri(), hostId, portId);
        } else {
            return null;
        }
    }

    public URI getHost() {
        if (getBaseUri() != null && hostId != null) {
            return ResourceUriBuilder.getHost(getBaseUri(), hostId);
        } else {
            return null;
        }
    }

    public URI getPort() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), portId);
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        return "hostId=" + hostId + ", interfaceName=" + interfaceName + ", " +
                "portId=" + portId;
    }

    // This group is used for validating the create process in which
    // the interface name must be provided.
    public interface HostInterfacePortCreateGroup extends Default {
    }
}
