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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

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

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID inboundFilterId;

    @ZoomField(name = "outbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID outboundFilterId;

    // TODO: Should this be @JsonIgnore?
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

    @JsonIgnore
    @ZoomField(name = "port_group_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> portGroupIds;

    @JsonIgnore
    @ZoomField(name = "trace_request_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> traceRequestIds;

    @ZoomField(name = "inbound_mirror_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> inboundMirrorIds;

    @ZoomField(name = "outbound_mirror_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> outboundMirrorIds;

    @JsonIgnore
    @ZoomField(name = "mirror_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> mirrorIds;

    @ZoomField(name = "insertion_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> insertionIds;

    @JsonIgnore
    @ZoomField(name = "l2insertion_infilter_id",
               converter = UUIDUtil.Converter.class)
    public UUID l2insertionInfilterId;

    @JsonIgnore
    @ZoomField(name = "l2insertion_outfilter_id",
               converter = UUIDUtil.Converter.class)
    public UUID l2insertionOutfilterId;

    @JsonIgnore
    @ZoomField(name = "srv_insertion_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> serviceInsertionIds;

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

    public URI getHost() {
        return absoluteUri(ResourceUris.HOSTS, hostId);
    }

    public URI getPeer() {
        return absoluteUri(ResourceUris.PORTS, peerId);
    }

    public URI getLink() {
        return relativeUri(ResourceUris.LINK);
    }

    public URI getPortGroups() {
        return relativeUri(ResourceUris.PORT_GROUPS);
    }

    public URI getHostInterfacePort() {
        return absoluteUri(ResourceUris.HOSTS, hostId,
                           ResourceUris.PORTS, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(Port from) {
        id = from.id;
        tunnelKey = from.tunnelKey;
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
        traceRequestIds = from.traceRequestIds;
        mirrorIds = from.mirrorIds;
        insertionIds = from.insertionIds;
        l2insertionInfilterId = from.l2insertionInfilterId;
        l2insertionOutfilterId = from.l2insertionOutfilterId;
        serviceInsertionIds = from.serviceInsertionIds;
    }

    protected ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("id", id)
            .add("adminStateUp", adminStateUp)
            .add("inboundFilterId", inboundFilterId)
            .add("outboundFilterId", outboundFilterId)
            .add("tunnelKey", tunnelKey)
            .add("vifId", vifId)
            .add("hostId", hostId)
            .add("interfaceName", interfaceName)
            .add("peerId", peerId)
            .add("portGroupIds", portGroupIds)
            .add("traceRequestIds", traceRequestIds)
            .add("active", active);
    }
}
