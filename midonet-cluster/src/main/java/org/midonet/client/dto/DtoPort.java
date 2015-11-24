/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.client.dto;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.base.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DtoBridgePort.class,
                name = PortType.BRIDGE),
        @JsonSubTypes.Type(value = DtoRouterPort.class,
                name = PortType.ROUTER),
        @JsonSubTypes.Type(value = DtoVxLanPort.class,
                name = PortType.VXLAN)})
public abstract class DtoPort {

    private UUID id;
    private UUID deviceId;
    private boolean adminStateUp = true;
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
    private URI peeringTable;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {

        return deviceId;
    }

    public URI getPeeringTable() {
        return peeringTable;
    }

    public void setPeeringTable(URI arpTable) {
        this.peeringTable = peeringTable;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
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

        if (adminStateUp != that.adminStateUp) {
            return false;
        }

        return Objects.equal(this.peeringTable, that.getPeeringTable());
    }
}
