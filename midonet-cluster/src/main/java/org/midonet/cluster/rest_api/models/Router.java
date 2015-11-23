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

import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.Router.class)
public class Router extends UriResource {

    public static final int MIN_ROUTER_NAME_LEN = 0;
    public static final int MAX_ROUTER_NAME_LEN = 255;

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @Size(min = MIN_ROUTER_NAME_LEN, max = MAX_ROUTER_NAME_LEN)
    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID inboundFilterId;
    @ZoomField(name = "outbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID outboundFilterId;

    @ZoomField(name = "load_balancer_id", converter = UUIDUtil.Converter.class)
    public UUID loadBalancerId;

    @JsonIgnore
    @ZoomField(name = "port_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> portIds;

    @JsonIgnore
    @ZoomField(name = "route_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> routeIds;

    @ZoomField(name = "as_number")
    public int asNumber;

    @JsonIgnore
    @ZoomField(name = "bgp_network_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> bgpNetworkIds;

    @JsonIgnore
    @ZoomField(name = "bgp_peer_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> bgpPeerIds;

    @JsonIgnore
    @ZoomField(name = "trace_request_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> traceRequestIds;

    @ZoomField(name = "inbound_mirror_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> inboundMirrorIds;

    @ZoomField(name = "outbound_mirror_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> outboundMirrorIds;

    @ZoomField(name = "local_redirect_chain_id", converter = UUIDUtil.Converter.class)
    public UUID localRedirectChainId;

    public Router() {
        adminStateUp = true;
    }

    public Router(URI baseUri) {
        adminStateUp = true;
        setBaseUri(baseUri);
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.ROUTERS, id);
    }

    public URI getInboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, inboundFilterId);
    }

    public URI getOutboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, outboundFilterId);
    }

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }

    public URI getPeerPorts() {
        return relativeUri(ResourceUris.PEER_PORTS);
    }

    public URI getRoutes() {
        return relativeUri(ResourceUris.ROUTES);
    }

    public URI getLoadBalancer() {
        return absoluteUri(ResourceUris.LOAD_BALANCERS, loadBalancerId);
    }

    public URI getBgpNetworks() {
        return relativeUri(ResourceUris.BGP_NETWORKS);
    }

    public URI getBgpPeers() {
        return relativeUri(ResourceUris.BGP_PEERS);
    }

    public URI getLocalRedirectChain() {
        return absoluteUri(ResourceUris.CHAINS, localRedirectChainId);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(Router from) {
        this.id = from.id;
        portIds = from.portIds;
        routeIds = from.routeIds;
        bgpNetworkIds = from.bgpNetworkIds;
        bgpPeerIds = from.bgpPeerIds;
        traceRequestIds = from.traceRequestIds;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("adminStateUp", adminStateUp)
            .add("inboundFilterId", inboundFilterId)
            .add("outboundFilterId", outboundFilterId)
            .add("loadBalancerId", loadBalancerId)
            .add("portIds", portIds)
            .add("routeIds", routeIds)
            .add("asNumber", asNumber)
            .add("bgpNetworkIds", bgpNetworkIds)
            .add("bgpPeerIds", bgpPeerIds)
            .add("traceRequestIds", traceRequestIds)
            .add("localRedirectChainId", localRedirectChainId)
            .toString();
    }
}
