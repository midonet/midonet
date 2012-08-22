/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsValidPortId;

import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Host interface - port map DTO
 */
@XmlRootElement
public class HostInterfacePortMap extends UriResource {

    @NotNull
    private UUID hostId;

    @IsValidPortId
    @NotNull
    private UUID portId;

    @NotNull(groups = HostInterfacePortMapCreateGroup.class)
    private String interfaceName;

    public HostInterfacePortMap(){
    }

    public HostInterfacePortMap(UUID hostId, String interfaceName,
                                UUID portId) {
        this.hostId = hostId;
        this.portId = portId;
        this.interfaceName = interfaceName;
    }

    public HostInterfacePortMap(UUID hostId,
                                HostDirectory.VirtualPortMapping mapping) {
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

    public URI getUri() {
        if (getBaseUri() != null && hostId != null) {
            return ResourceUriBuilder.getHostInterfacePortMap(
                    getBaseUri(), hostId);
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
    public interface HostInterfacePortMapCreateGroup extends Default {
    }
}
