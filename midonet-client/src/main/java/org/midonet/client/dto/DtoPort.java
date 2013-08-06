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
                name = PortType.BRIDGE),
        @JsonSubTypes.Type(value = DtoRouterPort.class,
                name = PortType.ROUTER)})
public abstract class DtoPort {

    private UUID id;
    private UUID deviceId;
    private UUID inboundFilterId;
    private UUID outboundFilterId;
    private URI inboundFilter;
    private URI outboundFilter;
    private UUID vifId;
    private URI uri;
    private URI portGroups;
    private URI hostInterfacePort;
    private UUID hostId;
    private URI host;
    private String interfaceName;
    private UUID peerId;
    private URI peer;
    private URI link;

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

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public UUID getPeerId() {
        return peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    public URI getPeer() {
        return peer;
    }

    public void setPeer(URI peer) {
        this.peer = peer;
    }

    public URI getLink() {
        return link;
    }

    public void setLink(URI link) {
        this.link = link;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public URI getHost() {
        return host;
    }

    public void setHost(URI host) {
        this.host = host;
    }

    public String getNetworkAddress() {
        return null;
    }

    public int getNetworkLength() {
        return -1;
    }

    public String getPortAddress() {
        return null;
    }

    public String getPortMac() {
        return null;
    }

    public URI getBgps() {
        return null;
    }

    public Short getVlanId() {
        return null;
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

        if (hostId != null
                ? !hostId.equals(that.hostId) : that.hostId != null) {
            return false;
        }

        if (host != null ? !host.equals(that.host) : that.host != null) {
            return false;
        }

        if (interfaceName != null
                ? !interfaceName.equals(that.interfaceName)
                : that.interfaceName != null) {
            return false;
        }

        if (peerId != null
                ? !peerId.equals(that.peerId) : that.peerId != null) {
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
