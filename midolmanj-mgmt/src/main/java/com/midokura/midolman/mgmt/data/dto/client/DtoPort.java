/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import com.midokura.midolman.mgmt.data.dto.PortType;

@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DtoBridgePort.class, name = PortType.MATERIALIZED_BRIDGE),
        @JsonSubTypes.Type(value = DtoLogicalBridgePort.class, name = PortType.LOGICAL_BRIDGE),
        @JsonSubTypes.Type(value = DtoMaterializedRouterPort.class, name = PortType.MATERIALIZED_ROUTER),
        @JsonSubTypes.Type(value = DtoLogicalRouterPort.class, name = PortType.LOGICAL_ROUTER) })
public abstract class DtoPort {
    private UUID id = null;
    private UUID deviceId = null;
    private UUID inboundFilter = null;
    private UUID outboundFilter = null;
    private UUID[] portGroupIDs = null;
    private UUID vifId = null;
    private URI uri;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

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

    public UUID getVifId() {
        return vifId;
    }

    public void setVifId(UUID vifId) {
        this.vifId = vifId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public abstract String getType();

    @Override
    public boolean equals(Object o) {

        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        DtoPort that = (DtoPort) o;

        if (id != null ? !id.equals(that.id) : that.id != null) {
            return false;
        }

        if (deviceId != null ? !deviceId.equals(that.deviceId)
                : that.deviceId != null) {
            return false;
        }

        if (inboundFilter != null ? !inboundFilter.equals(that.inboundFilter)
                : that.inboundFilter != null) {
            return false;
        }

        if (outboundFilter != null ? !outboundFilter
                .equals(that.outboundFilter) : that.outboundFilter != null) {
            return false;
        }

        if (portGroupIDs != null ? !portGroupIDs.equals(that.portGroupIDs)
                : that.portGroupIDs != null) {
            return false;
        }

        if (uri != null ? !uri.equals(that.uri) : that.uri != null) {
            return false;
        }

        return true;
    }
}
