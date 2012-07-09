/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;

/**
 * Class representing port.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = MaterializedBridgePort.class, name = PortType.MATERIALIZED_BRIDGE),
        @JsonSubTypes.Type(value = LogicalBridgePort.class, name = PortType.LOGICAL_BRIDGE),
        @JsonSubTypes.Type(value = MaterializedRouterPort.class, name = PortType.MATERIALIZED_ROUTER),
        @JsonSubTypes.Type(value = LogicalRouterPort.class, name = PortType.LOGICAL_ROUTER) })
public abstract class Port extends UriResource {

    /**
     * Port ID
     */
    protected UUID id = null;

    /**
     * Device ID
     */
    protected UUID deviceId = null;

    /**
     * Inbound Filter Chain ID
     */
    protected UUID inboundFilterId = null;

    /**
     * Outbound Filter Chain ID
     */
    protected UUID outboundFilterId = null;

    /**
     * List of Port Groups to which this port belongs.
     */
    protected UUID[] portGroupIDs = null;

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
     * @param id
     * @param config
     */
    public Port(UUID id, PortConfig config) {
        this.id = id;
        this.deviceId = config.device_id;
        this.inboundFilterId = config.inboundFilter;
        this.outboundFilterId = config.outboundFilter;
        if (config.portGroupIDs != null && config.portGroupIDs.size() > 0) {
            this.portGroupIDs = config.portGroupIDs
                    .toArray(new UUID[config.portGroupIDs.size()]);
        }
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
     * logical Set device ID.
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

    public UUID[] getPortGroupIDs() {
        return portGroupIDs;
    }

    public void setPortGroupIDs(UUID[] portGroupIDs) {
        this.portGroupIDs = portGroupIDs;
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
     * Convert this object to PortConfig object.
     *
     * @return PortConfig object.
     */
    public abstract PortConfig toConfig();

    /**
     * Set the PortConfig fields
     *
     * @param config
     *            PortConfig object
     */
    public void setConfig(PortConfig config) {
        config.device_id = deviceId;
        config.inboundFilter = inboundFilterId;
        config.outboundFilter = outboundFilterId;
        if (this.portGroupIDs != null) {
            config.portGroupIDs = new HashSet<UUID>(
                    Arrays.asList(this.portGroupIDs));
        } else {
            config.portGroupIDs = new HashSet<UUID>();
        }
    }

    /**
     * @return whether this port is a logical port
     */
    @XmlTransient
    public abstract boolean isLogical();

    /**
     * @return ID of the attached resource
     */
    @XmlTransient
    public abstract UUID getAttachmentId();

    /**
     * @param port
     *            Port to check linkability with.
     * @return True if two ports can be linked.
     */
    public boolean isLinkable(Port port) {

        if (port == null) {
            throw new IllegalArgumentException("port cannot be null");
        }

        // Must be two logical ports
        if (!isLogical() || !port.isLogical()) {
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

        // Cannot link two bridges
        if (!isRouterPort() && !port.isRouterPort()) {
            return false;
        }

        // If two routers, must be on separate devices
        if (isRouterPort() && port.isRouterPort()) {
            if (deviceId == port.getDeviceId()) {
                return false;
            }
        }

        // Finally, both ports must be unlinked
        return (getAttachmentId() == null && port.getAttachmentId() == null);
    }

    /**
     * @return True if it's a router port. False if it's a bridge port.
     */
    @XmlTransient
    public abstract boolean isRouterPort();

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
}
