/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midonet.api.network;

import com.midokura.midonet.api.network.validation.IsValidPortId;
import com.midokura.midonet.api.network.validation.PortsLinkable;

import javax.validation.GroupSequence;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

/**
 * Port link DTO
 */
@PortsLinkable(groups = Link.LinkCreateGroupExtended.class)
@XmlRootElement
public class Link {

    @IsValidPortId
    private UUID portId;

    @IsValidPortId(groups = LinkCreateGroup.class)
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

    public boolean isUnlink() {
        return this.peerId == null;
    }

    /**
     * Interface used for validating a link on create.
     */
    public interface LinkCreateGroup {
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
    @GroupSequence({ Default.class, LinkCreateGroup.class,
            LinkCreateGroupExtended.class})
    public interface LinkCreateGroupSequence {
    }

    /**
     * Interface that defines the ordering of validation groups for link
     * delete.
     */
    @GroupSequence({ Default.class })
    public interface LinkDeleteGroupSequence {
    }
}
