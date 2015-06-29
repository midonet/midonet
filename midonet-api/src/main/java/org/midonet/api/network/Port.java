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
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;

import javax.validation.GroupSequence;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.version.Since;

/**
 * Class representing port.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BridgePort.class,
                name = PortType.BRIDGE),
        @JsonSubTypes.Type(value = RouterPort.class,
                name = PortType.ROUTER),
        @JsonSubTypes.Type(value = ExteriorBridgePort.class,
                name = PortType.EXTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = InteriorBridgePort.class,
                name = PortType.INTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = ExteriorRouterPort.class,
                name = PortType.EXTERIOR_ROUTER),
        @JsonSubTypes.Type(value = InteriorRouterPort.class,
                name = PortType.INTERIOR_ROUTER),
        @JsonSubTypes.Type(value = VxLanPort.class,
                name = PortType.VXLAN)})
public abstract class Port extends UriResource {

    /**
     * Port ID
     */
    protected UUID id;

    /**
     * Administrative state.
     */

    protected boolean adminStateUp;

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
     * Host ID where the port is bound to
     */
    @Since("2")
    protected UUID hostId;

    /**
     * Interface name where the port is bound to
     */
    @Since("2")
    protected String interfaceName;

    /**
     * Peer port ID
     */
    protected UUID peerId;

    protected boolean active = false;

    /**
     * Default constructor
     */
    public Port() {
        adminStateUp = true;
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
        this();
        this.id = id;
        this.deviceId = deviceId;
    }

    public Port(org.midonet.cluster.data.Port<?, ?> portData) {
        this(UUID.fromString(portData.getId().toString()),
                portData.getDeviceId());
        this.adminStateUp = portData.isAdminStateUp();
        this.inboundFilterId = portData.getInboundFilter();
        this.outboundFilterId = portData.getOutboundFilter();
        this.hostId = portData.getHostId();
        this.interfaceName = portData.getInterfaceName();
        this.peerId = portData.getPeerId();
        this.active = portData.isActive();
        if (portData.getProperty(org.midonet.cluster.data.Port.Property.vif_id)
                != null) {
            this.vifId = UUID.fromString(portData.getProperty(
                    org.midonet.cluster.data.Port.Property.vif_id));
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
    public abstract URI getDevice();

    /**
     * Set device ID.
     *
     * @param deviceId
     *            ID of the device.
     */
    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * Get administrative state
     *
     * @return administrative state of the port.
     */

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Set administrative state
     *
     * @param adminStateUp
     *            administrative state of the port.
     */
    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
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
    public abstract org.midonet.cluster.data.Port<?, ?> toData();

    /**
     * Set the Port data fields
     *
     * @param data
     *            Port data object
     */
    public void setConfig(org.midonet.cluster.data.Port<?, ?> data) {
        data.setId(this.id);
        data.setAdminStateUp(this.adminStateUp);
        data.setDeviceId(this.deviceId);
        data.setInboundFilter(this.inboundFilterId);
        data.setOutboundFilter(this.outboundFilterId);
        data.setHostId(this.hostId);
        data.setInterfaceName(this.interfaceName);
        data.setPeerId(this.peerId);
        if (vifId != null) {
            data.setProperty(org.midonet.cluster.data.Port.Property.vif_id,
                    vifId.toString());
        }
    }

    /**
     * @return whether this port is a interior port
     */
    @XmlTransient
    public boolean isInterior() {
        return peerId != null;
    }

    @XmlTransient
    public boolean isExterior() {
        return hostId != null && interfaceName != null;
    }

    /**
     * An unplugged port can become interior or exterior
     * depending on what it is attached to later.
     */
    @XmlTransient
    public boolean isUnplugged() {
        return !isInterior() && !isExterior();
    }

    public UUID getPeerId() {
        return peerId;
    }
    public void setPeerId(UUID _peerId) {
        if(isExterior() && _peerId != null) {
            throw new RuntimeException("Cannot add a peerId to an exterior" +
                    "port");
        }
        peerId = _peerId;
    }


    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        if(isInterior() && interfaceName != null) {
            throw new RuntimeException("Cannot add a interface to an interior" +
                    "port");
        }
        this.interfaceName = interfaceName;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        if(isInterior() && hostId != null) {
            throw new RuntimeException("Cannot add a hostId to an interior" +
                    "port");
        }
        this.hostId = hostId;
    }


    @Since("2")
    public URI getHost() {
        if (getBaseUri() != null && hostId != null) {
            return ResourceUriBuilder.getHost(getBaseUri(), hostId);
        } else {
            return null;
        }
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
        vifId = _vifId;
    }

    /**
     * @param port Port to check linkability with.
     * @return True if two ports can be linked.
     */
    public abstract boolean isLinkable(Port port);

    /**
     * @return The port type
     */
    public abstract String getType();

    /**
     * VLAN ID attached to the port, if appropriate
     *
     * @return ID of VLAN
     */
    public Short getVlanId() {
        return -1;
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

    @Override
    public String toString() {
        return "id=" + id + ", deviceId=" + deviceId +
               ", adminStateUp=" + adminStateUp +
               ", inboundFilterId=" + inboundFilterId +
               ", outboundFilterId=" + outboundFilterId;
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
