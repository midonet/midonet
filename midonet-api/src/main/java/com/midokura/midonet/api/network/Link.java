/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midonet.api.network;

import com.midokura.midonet.api.ResourceUriBuilder;
import com.midokura.midonet.api.UriResource;
import com.midokura.midonet.api.network.validation.IsValidPortId;
import com.midokura.midonet.api.network.validation.PortsLinkable;

import javax.validation.GroupSequence;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Port link DTO
 */
@PortsLinkable(groups = Link.LinkCreateGroupExtended.class)
@XmlRootElement
public class Link extends UriResource {

    @IsValidPortId
    private UUID portId;

    @IsValidPortId
    private UUID peerId;

    public Link() {
    }

    public Link(UUID portId, UUID peerId) {
        this.portId = portId;
        this.peerId = peerId;
    }

    public UUID getPortId() {
        return this.portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public UUID getPeerId() {
        return this.peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    public URI getPort() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), portId);
        } else {
            return null;
        }
    }

    public URI getPeer() {
        if (getBaseUri() != null && peerId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerId);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPortLink(getBaseUri(), portId);
        } else {
            return null;
        }
    }

    /**
     * Interface used for validating a link on create.
     */
    public interface LinkCreateGroupExtended {
    }


    /**
     * Interface that defines the ordering of validation groups for link
     * create.
     */
    @GroupSequence({ Default.class, LinkCreateGroupExtended.class})
    public interface LinkCreateGroupSequence {
    }

}
