/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.UriResource;
import org.midonet.api.network.validation.IsValidPortId;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.network.validation.IsValidPortGroupId;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import java.net.URI;
import java.util.UUID;

/**
 * DTO to represent a membership of a port in a port group.
 */
public class PortGroupPort extends UriResource {

    @NotNull
    @IsValidPortGroupId(groups = PortGroupPortCreateExtended.class)
    private UUID portGroupId;

    @NotNull
    @IsValidPortId(groups = PortGroupPortCreateExtended.class)
    private UUID portId;

    public PortGroupPort(){
    }

    public UUID getPortGroupId() {
        return portGroupId;
    }

    public void setPortGroupId(UUID portGroupId) {
        this.portGroupId = portGroupId;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && portGroupId != null && portId != null) {
            return ResourceUriBuilder.getPortGroupPort(getBaseUri(),
                    portGroupId, portId);
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

    public URI getPortGroup() {
        if (getBaseUri() != null && portGroupId != null) {
            return ResourceUriBuilder.getPortGroup(getBaseUri(), portGroupId);
        } else {
            return null;
        }
    }

    public interface PortGroupPortCreateExtended {
    }

    @GroupSequence({ Default.class, PortGroupPortCreateExtended.class})
    public interface PortGroupPortCreateGroupSequence {
    }


}
