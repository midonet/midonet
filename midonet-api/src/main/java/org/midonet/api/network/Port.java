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
     * VIF ID
     */
    protected UUID vifId;

    /**
     * Host ID required to generate `hostInterfacePort` property.
     */
    protected UUID hostId;
    /**
     * Peer port ID
     */
    protected UUID peerId;


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
    public boolean isInterior() {
        return peerId != null;
    }

    public boolean isExterior() {
        return hostId != null && vifId != null;
    }

    /**
     * An unplugged port can become interior or exterior
     * depending on what it is attached to later.
     */
    public boolean isUnplugged() {
        return !isInterior() && !isExterior();
    }

    /**
     * @return ID of the attached resource
     */
    @XmlTransient
    public UUID getAttachmentId() {
        if (isInterior())
            return getPeerId();
        else if (isExterior())
            return getVifId();
        else
            return null;
    }

    public UUID getPeerId() {
        return peerId;
    }
    public void setPeerId(UUID _peerId) {
        if(isExterior()) {
            throw new RuntimeException("Cannot add a peerId to an exterior" +
                    "port");
        }
        peerId = _peerId;
    }

    /**
     * @return the peer port URI
     */
    public URI getPeer() {
        if (peerId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerId);
        } else {
            return null;
        }
    }

    public URI getLink() {
        if (id != null) {
            return ResourceUriBuilder.getPortLink(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public UUID getVifId() {
        return vifId;
    }

    public void setVifId(UUID _vifId) {
        if(isInterior())
            throw new RuntimeException("Cannot set vifId" +
                    "on an Interior port.");
        vifId = _vifId;
    }



    /**
     * @param port Port to check linkability with.
     * @return True if two ports can be linked.
     */
    public abstract boolean isLinkable(Port port);


    /**
     * @return True if it's a vlan bridge port. False otherwise.
     */
    @XmlTransient
    public abstract boolean isVlanBridgePort();

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

    /**
     * Getter to be used to generate "host-interface-port" property's value.
     *
     * <code>host-interface-port</code> property in the JSON representation
     * of this client-side port DTO object would be generated by this method
     * automatically.
     *
     * @return the URI of the host-interface-port binding
     */
    public URI getHostInterfacePort() {
        if (getBaseUri() != null && this.hostId != null &&
                this.getId() != null) {
            return ResourceUriBuilder.getHostInterfacePort(
                    getBaseUri(), this.hostId, this.getId());
        } else {
            return null;
        }
    }

}
