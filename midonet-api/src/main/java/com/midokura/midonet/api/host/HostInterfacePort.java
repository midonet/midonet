/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.host;

import com.midokura.midonet.api.ResourceUriBuilder;
import com.midokura.midonet.api.UriResource;
import com.midokura.midonet.api.network.validation.IsValidPortId;
import com.midokura.midonet.cluster.data.host.VirtualPortMapping;

import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Host interface - port map DTO
 */
@XmlRootElement
public class HostInterfacePort extends UriResource {

    @NotNull
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
