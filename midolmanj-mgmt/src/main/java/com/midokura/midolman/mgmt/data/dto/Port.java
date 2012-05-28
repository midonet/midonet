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

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.ZkNodeEntry;

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
    protected UUID inboundFilter = null;

    /**
     * Outbound Filter Chain ID
     */
    protected UUID outboundFilter = null;

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
     * @param mgmtConfig
     */
    public Port(UUID id, PortConfig config, PortMgmtConfig mgmtConfig) {
        this.id = id;
        this.deviceId = config.device_id;
        this.inboundFilter = config.inboundFilter;
        this.outboundFilter = config.outboundFilter;
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

    public UUID getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(UUID inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public UUID getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(UUID outboundFilter) {
        this.outboundFilter = outboundFilter;
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
     * Convert this object to PortMgmtConfig object.
     *
     * @return PortConfig object.
     */
    public PortMgmtConfig toMgmtConfig() {
        return null;
    }

    /**
     * Set the PortConfig fields
     *
     * @param config
     *            PortConfig object
     */
    public void setConfig(PortConfig config) {
        config.device_id = deviceId;
        config.inboundFilter = inboundFilter;
        config.outboundFilter = outboundFilter;
        if (this.portGroupIDs != null) {
            config.portGroupIDs = new HashSet<UUID>(
                    Arrays.asList(this.portGroupIDs));
        } else {
            config.portGroupIDs = new HashSet<UUID>();
        }
    }

    /**
     * Convert this object to ZkNodeEntry
     *
     * @return ZkNodeEntry
     */
    public ZkNodeEntry<UUID, PortConfig> toZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(id, toConfig());
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
        return "id=" + id + ", deviceId=" + deviceId;
    }
}
