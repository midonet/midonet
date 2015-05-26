/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Resource;
import javax.ws.rs.core.UriBuilder;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.util.version.Since;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BridgePort.class, name = PortType.BRIDGE),
        @JsonSubTypes.Type(value = RouterPort.class, name = PortType.ROUTER),
        @JsonSubTypes.Type(value = ExteriorBridgePort.class, name = PortType.EXTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = InteriorBridgePort.class, name = PortType.INTERIOR_BRIDGE),
        @JsonSubTypes.Type(value = ExteriorRouterPort.class, name = PortType.EXTERIOR_ROUTER),
        @JsonSubTypes.Type(value = InteriorRouterPort.class, name = PortType.INTERIOR_ROUTER),
        @JsonSubTypes.Type(value = VxLanPort.class, name = PortType.VXLAN)})
@ZoomClass(clazz = Topology.Port.class, factory = Port.PortFactory.class)
public abstract class Port extends UriResource {

    public static final class PortFactory implements ZoomConvert.Factory<Port, Topology.Port> {
        @Override
        public Class<? extends Port> getType(Topology.Port proto) {
            if (proto.hasVtepId()) return VxLanPort.class;
            else if (proto.hasNetworkId()) return BridgePort.class;
            else if (proto.hasRouterId()) return RouterPort.class;
            else throw new IllegalArgumentException("Unknown port type: " + proto);
        }
    }

    public class PortType {
        public static final String BRIDGE = "Bridge";
        public static final String ROUTER = "Router";
        public static final String EXTERIOR_BRIDGE = "ExteriorBridge";
        public static final String EXTERIOR_ROUTER = "ExteriorRouter";
        public static final String INTERIOR_BRIDGE = "InteriorBridge";
        public static final String INTERIOR_ROUTER = "InteriorRouter";
        public static final String VXLAN = "Vxlan";
    }

    private static AtomicLong tunnelKeyGenerator = new AtomicLong();

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID inboundFilterId;

    @ZoomField(name = "outbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID outboundFilterId;

    @ZoomField(name = "tunnel_key")
    public long tunnelKey;

    @ZoomField(name = "vif_id", converter = UUIDUtil.Converter.class)
    public UUID vifId;

    @ZoomField(name = "host_id", converter = UUIDUtil.Converter.class)
    private UUID hostId;

    @ZoomField(name = "interface_name")
    private String interfaceName;

    @ZoomField(name = "peer_id", converter = UUIDUtil.Converter.class)
    private UUID peerId;

    @JsonIgnore
    @ZoomField(name = "port_group_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> portGroupIds;

    public boolean active;

    public Port() {
        adminStateUp = true;
        active = false;
    }

    public abstract UUID getDeviceId();

    public abstract void setDeviceId(UUID deviceId);

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.PORTS, id);
    }

    @Since("2")
    public URI getHost() {
        return absoluteUri(ResourceUris.HOSTS, hostId);
    }

    public URI getPeer() {
        return absoluteUri(ResourceUris.PORTS, peerId);
    }

    public URI getLink() {
        return relativeUri(ResourceUris.LINK);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        if (0 == tunnelKey) {
            tunnelKey = tunnelKeyGenerator.incrementAndGet();
        }
    }

    @JsonIgnore
    public void update(Port from) {
        this.id = from.id;
        if (null != from.hostId) {
            hostId = from.hostId;
        }
        if (null != from.interfaceName) {
            interfaceName = from.interfaceName;
        }
        if (null != from.peerId) {
            peerId = from.peerId;
        }
        portGroupIds = from.portGroupIds;
    }

    @Since("2")
    public final String getInterfaceName() {
        return this.interfaceName;
    }

    public final void setInterfaceName(String s) {
        if (this.isInterior() && interfaceName != null) {
            throw new IllegalArgumentException(
                "Interior ports cannot have interface names");
        }
        this.interfaceName = s;
    }

    public final UUID getHostId() {
        return hostId;
    }

    public final void setHostId(UUID hostId) {
        if(isInterior() && hostId != null) {
            throw new IllegalArgumentException(
                "Cannot add a hostId to an interior port");
        }
        this.hostId = hostId;
    }

    @Since("2")
    public final UUID getPeerId() {
        return peerId;
    }

    public final void setPeerId(UUID _peerId) {
        if(isExterior() && _peerId != null) {
            throw new IllegalArgumentException(
                "Cannot add a peerId to an exterior port");
        }
        peerId = _peerId;
    }

    /**
     * @param port Port to check linkability with.
     * @return True if two ports can be linked.
     */
    @JsonIgnore
    public abstract boolean isLinkable(Port port);

    /**
     * An unplugged port can become interior or exterior
     * depending on what it is attached to later.
     */
    @JsonIgnore
    public boolean isUnplugged() {
        return !isInterior() && !isExterior();
    }

    /**
     * @return whether this port is a interior port
     */
    @JsonIgnore
    public boolean isInterior() {
        return peerId != null;
    }

    @JsonIgnore
    public boolean isExterior() {
        return hostId != null && interfaceName != null;
    }

    public URI getHostInterfacePort() {
        if (hostId == null) {
            return null;
        }
        return  UriBuilder.fromUri(absoluteUri(ResourceUris.HOSTS, hostId))
                          .path(ResourceUris.PORTS).path(id.toString())
                          .build();
    }

    public URI getInboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, inboundFilterId);
    }

    public URI getOutboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, outboundFilterId);
    }

    public URI getPortGroups() {
        return relativeUri(ResourceUris.PORT_GROUPS);
    }

}
