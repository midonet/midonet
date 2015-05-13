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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.bind.annotation.XmlRootElement;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.util.UUIDUtil;

@XmlRootElement
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
@Resource(name = ResourceUris.PORTS)
@ZoomClass(clazz = Topology.Port.class, factory = Port.PortFactory.class)
public abstract class Port extends UriResource<UUID> {

    private static AtomicLong tunnelKeySeed = new AtomicLong();

    @ResourceId
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;

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
    public UUID hostId;

    @ZoomField(name = "interface_name")
    public String interfaceName;

    @ZoomField(name = "peer_id", converter = UUIDUtil.Converter.class)
    public UUID peerId;

    public Port() {
        adminStateUp = true;
    }

    public abstract UUID getDeviceId();

    public abstract void setDeviceId(UUID deviceId);

    public static final class PortFactory implements ZoomConvert.Factory<Port, Topology.Port> {
        @Override
        public Class<? extends Port> getType(Topology.Port proto) {
            if (proto.hasVtepId()) return VxLanPort.class;
            else if (proto.hasNetworkId()) return BridgePort.class;
            else if (proto.hasRouterId()) return RouterPort.class;
            else throw new IllegalArgumentException("Unknown port type: " + proto);
        }
    }

    @Override
    public UUID getId() {
        return this.id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public void beforeToProto() {
        if (0 == tunnelKey) {
            tunnelKey = tunnelKeySeed.incrementAndGet();
        }
    }

    public URI getHost() {
        return absoluteUri(ResourceUris.HOSTS, hostId);
    }

    public URI getPeer() {
        return absoluteUri(ResourceUris.PORTS, peerId);
    }

    public URI getLink() {
        return relativeUri(ResourceUris.LINK);
    }
}
