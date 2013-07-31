/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.validation.GroupSequence;
import javax.validation.constraints.AssertFalse;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing port.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TrunkPort.class,
                name = PortType.TRUNK_VLAN_BRIDGE),
        @JsonSubTypes.Type(value = InteriorVlanBridgePort.class,
                name = PortType.INTERIOR_VLAN_BRIDGE),
        @JsonSubTypes.Type(value = ExteriorBridgePort.class,
                name = PortType.EXTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = InteriorBridgePort.class,
                name = PortType.INTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = ExteriorRouterPort.class,
                name = PortType.EXTERIOR_ROUTER),
        @JsonSubTypes.Type(value = InteriorRouterPort.class,
                name = PortType.INTERIOR_ROUTER) })
public abstract class Port extends UriResource {

    /**
     * Port ID
     */
    protected UUID id;

    /**
     * Device ID
     */
    protected UUID deviceId;

    /**
     * Inbound Filter Chain ID
     */
    protected UUID inboundFilterId;

    /**
     * Outbound Filter Chain ID
     */
    protected UUID outboundFilterId;


    /**
     * Default constructor
     */
    public Port() {
    }

    /**
     * Constructor
     *
     * @param id
     *            Port ID
     * @param deviceId
     *            Device ID
     */
    public Port(UUID id, UUID deviceId) {
        this.id = id;
        this.deviceId = deviceId;
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public Port(org.midonet.cluster.data.Port portData) {
        this(UUID.fromString(portData.getId().toString()),
                portData.getDeviceId());
        this.inboundFilterId = portData.getInboundFilter();
        this.outboundFilterId = portData.getOutboundFilter();
    }

    /**
     * Get port ID.
     *
     * @return port ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set port ID.
     *
     * @param id
     *            ID of the port.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get device ID.
     *
     * @return device ID.
     */
    public UUID getDeviceId() {
        return deviceId;
    }

    /**
     * @return the device URI
     */
    abstract public URI getDevice();

    /**
     * Set device ID.
     *
     * @param deviceId
     *            ID of the device.
     */
    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getInboundFilterId() {
        return inboundFilterId;
    }

    public URI getInboundFilter() {
        if (getBaseUri() != null && inboundFilterId != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), inboundFilterId);
        } else {
            return null;
        }
    }

    public void setInboundFilterId(UUID inboundFilterId) {
        this.inboundFilterId = inboundFilterId;
    }

    public UUID getOutboundFilterId() {
        return outboundFilterId;
    }

    public void setOutboundFilterId(UUID outboundFilterId) {
        this.outboundFilterId = outboundFilterId;
    }

    public URI getOutboundFilter() {
        if (getBaseUri() != null && outboundFilterId != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), outboundFilterId);
        } else {
            return null;
        }
    }

    public URI getPortGroups() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPortPortGroups(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to Port data object.
     *
     * @return Port data object.
     */
    public abstract org.midonet.cluster.data.Port toData();

    /**
     * Set the Port data fields
     *
     * @param data
     *            Port data object
     */
    public void setConfig(org.midonet.cluster.data.Port data) {
        data.setId(this.id);
        data.setDeviceId(this.deviceId);
        data.setInboundFilter(this.inboundFilterId);
        data.setOutboundFilter(this.outboundFilterId);
    }

    /**
     * @return whether this port is a interior port
     */
    @XmlTransient
    public abstract boolean isInterior();

    /**
     * @return ID of the attached resource
     */
    @XmlTransient
    public abstract UUID getAttachmentId();

    /**
     * @param port Port to check linkability with.
     * @return True if two ports can be linked.
     */
    public boolean isLinkable(Port port) {

        if (port == null) {
            throw new IllegalArgumentException("port cannot be null");
        }

        // Must be two interior ports
        if (!isInterior() || !port.isInterior()) {
            return false;
        }

        // IDs must be set
        if (id == null || port.getId() == null) {
            return false;
        }

        // IDs must not be the same
        if (id == port.getId()) {
            return false;
        }

        // If both are bridge ports allowed as long as only one has VLAN ID
        if (isBridgePort() && port.isBridgePort()) {
            Short myVlanId = ((InteriorBridgePort)this).getVlanId();
            Short herVlanId = ((InteriorBridgePort)port).getVlanId();
            if ((myVlanId == null && herVlanId == null) ||
                (myVlanId != null && herVlanId != null)) {
                return false;
            }
        }

        // If two routers, must be on separate devices
        if (isRouterPort() && port.isRouterPort()) {
            if (deviceId == port.getDeviceId()) {
                return false;
            }
        }

        // Cannot link vlan bridges with anything else but interior br. ports
        if (isVlanBridgePort() || port.isVlanBridgePort()) {
            Port nonVlanPort = isVlanBridgePort() ? port : this;
            if (!nonVlanPort.isBridgePort() && !nonVlanPort.isInterior()) {
                return false;
            }
        }

        // Finally, both ports must be unlinked
        return (getAttachmentId() == null && port.getAttachmentId() == null);
    }

    /**
     * @return True if it's a router port. False otherwise.
     */
    @XmlTransient
    public abstract boolean isRouterPort();

    /**
     * @return True if it's a vlan bridge port. False otherwise.
     */
    @XmlTransient
    public abstract boolean isVlanBridgePort();

    /**
     * @return True if it's a bridge port. False otherwise.
     */
    @XmlTransient
    public abstract boolean isBridgePort();

    /**
     * @param id
     *            Attachment resource ID
     */
    public abstract void setAttachmentId(UUID id);

    /**
     * @return　The port type
     */
    public abstract String getType();

    /**
     * Checks whether this object can be deleted.
     */
    @AssertFalse(groups = PortDeleteGroup.class)
    public boolean hasAttachment() {
        return (getAttachmentId() != null);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", deviceId=" + deviceId + ", inboundFilterId="
                + inboundFilterId + ", outboundFilterId=" + outboundFilterId;
    }

    /**
     * Interface used for validating a port on delete.
     */
    public interface PortDeleteGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for port
     * delete.
     */
    @GroupSequence({ PortDeleteGroup.class })
    public interface PortDeleteGroupSequence {
    }

}
