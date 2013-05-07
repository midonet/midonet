/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DtoBridgePort.class,
                name = PortType.EXTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = DtoInteriorBridgePort.class,
                name = PortType.INTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = DtoExteriorRouterPort.class,
                name = PortType.EXTERIOR_ROUTER),
        @JsonSubTypes.Type(value = DtoInteriorRouterPort.class,
                name = PortType.INTERIOR_ROUTER)})
public abstract class DtoPort {
    private UUID id = null;
    private UUID deviceId = null;
    private UUID inboundFilterId = null;
    private UUID outboundFilterId = null;
    private URI inboundFilter = null;
    private URI outboundFilter = null;
    private UUID vifId = null;
    private URI uri;
    private URI portGroups;
    private URI hostInterfacePort = null;

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

    public UUID getInboundFilterId() {
        return inboundFilterId;
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

    public URI getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(URI inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public URI getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(URI outboundFilter) {
        this.outboundFilter = outboundFilter;
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

    public URI getPortGroups() {
        return portGroups;
    }

    public void setPortGroups(URI portGroups) {
        this.portGroups = portGroups;
    }

    /**
     * Getter for the host-interface-port URI property in this storage-side
     * port DTO.
     *
     *
     * @return the URI of the host-interface-port associated with this
     *         client-side port DTO object
     */
    public URI getHostInterfacePort() {
        return hostInterfacePort;
    }

    /**
     * Setter for the host-interface-port URI property in this storage-side
     * port DTO.
     *
     * @param hostInterfacePort the URI of the host-interface-port associated
     *                          with this client-side port DTO object
     */
    public void setHostInterfacePort(URI hostInterfacePort) {
        this.hostInterfacePort = hostInterfacePort;
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

        if (inboundFilterId != null ? !inboundFilterId
                .equals(that.inboundFilterId) : that.inboundFilterId != null) {
            return false;
        }

        if (outboundFilterId != null ? !outboundFilterId
                .equals(that.outboundFilterId) : that.outboundFilterId != null) {
            return false;
        }

        if (uri != null ? !uri.equals(that.uri) : that.uri != null) {
            return false;
        }

        if (portGroups != null ? !portGroups.equals(that.portGroups)
                : that.portGroups != null) {
            return false;
        }
        if (hostInterfacePort != null ?
                !hostInterfacePort.equals(that.hostInterfacePort) :
                that.hostInterfacePort != null) {
            return false;
        }

        return true;
    }
}
