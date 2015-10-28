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

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoPortGroup;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.client.dto.DtoSystemState;
import org.midonet.client.dto.DtoTunnelZone;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_COLLECTION_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_CHAIN_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_CHAIN_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_COLLECTION_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_JSON_V5;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORTGROUP_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORTGROUP_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_COLLECTION_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTE_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_RULE_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_SYSTEM_STATE_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TENANT_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TENANT_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON;

public class Application extends ResourceBase<Application, DtoApplication> {

    DtoApplication app;
    WebResource resource;
    private static String ID_TOKEN = "{id}";

    public Application(WebResource resource, DtoApplication app) {
        this(resource, app, APPLICATION_JSON_V5());
    }

    public Application(WebResource resource, DtoApplication app,
                       String mediaType) {
        super(resource, null, app, mediaType);
        this.app = app;
        this.resource = resource;
    }

    @Override
    public URI getUri() {
        return app.getUri();
    }

    public String getVersion() {
        get();
        return app.getVersion();
    }

    public ResourceCollection<Tenant> getTenants(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getTenants(),
                queryParams,
                APPLICATION_TENANT_COLLECTION_JSON_V2(),
                Tenant.class, Tenant.class);
    }

    public ResourceCollection<Bridge> getBridges(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getBridges(),
                                 queryParams,
                                 APPLICATION_BRIDGE_COLLECTION_JSON_V4(),
                                 Bridge.class, DtoBridge.class);
    }

    public ResourceCollection<Router> getRouters(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getRouters(),
                                 queryParams,
                                 APPLICATION_ROUTER_COLLECTION_JSON_V3(),
                                 Router.class, DtoRouter.class);
    }

    public ResourceCollection<RuleChain> getChains(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getChains(),
                                 queryParams,
                                 APPLICATION_CHAIN_COLLECTION_JSON(),
                                 RuleChain.class, DtoRuleChain.class);
    }

    public ResourceCollection<PortGroup> getPortGroups(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getPortGroups(),
                queryParams,
                APPLICATION_PORTGROUP_COLLECTION_JSON(),
                PortGroup.class, DtoPortGroup.class);
    }

    public ResourceCollection<Host> getHosts(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getHosts(),
                                 queryParams,
                                 APPLICATION_HOST_COLLECTION_JSON_V3(),
                                 Host.class, DtoHost.class);
    }

    public ResourceCollection<TunnelZone>
            getTunnelZones(MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getTunnelZones(), queryParams,
            APPLICATION_TUNNEL_ZONE_COLLECTION_JSON(),
            TunnelZone.class, DtoTunnelZone.class);
    }

    public Bridge addBridge() {
        return new Bridge(resource, principalDto.getBridges(),
                          new DtoBridge());
    }

    public Router addRouter() {
        return new Router(resource, principalDto.getRouters(),
                          new DtoRouter());
    }

    public RuleChain addChain() {
        return new RuleChain(resource, principalDto.getChains(),
                             new DtoRuleChain());
    }

    public PortGroup addPortGroup() {
        return new PortGroup(resource, principalDto.getPortGroups(),
                new DtoPortGroup());
    }

    public TunnelZone addGreTunnelZone() {
        return new TunnelZone(resource,
                principalDto.getTunnelZones(),
                new DtoTunnelZone(),
                APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON());
    }

    public Bridge getBridge(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getBridgeTemplate(), ID_TOKEN, id);
        DtoBridge bridge = resource.get(uri, null, DtoBridge.class,
                APPLICATION_BRIDGE_JSON_V4());
        return new Bridge(resource, null, bridge);
    }

    public Host getHost(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getHostTemplate(), ID_TOKEN, id);
        DtoHost host = resource.get(uri, null, DtoHost.class,
                APPLICATION_HOST_JSON_V3());
        return new Host(resource, null, host);
    }

    public Port<?,?> getPort(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getPortTemplate(), ID_TOKEN, id);
        DtoPort port = resource.get(uri, null, DtoPort.class,
                APPLICATION_PORT_V3_JSON());
        if (port instanceof DtoBridgePort) {
            return new BridgePort(resource, null, (DtoBridgePort) port);
        } else if (port instanceof  DtoRouterPort) {
            return new RouterPort(resource, null, (DtoRouterPort) port);
        } else {
            throw new IllegalArgumentException(
                    "No port with ID (" + id + ") exists.");
        }
    }

    public PortGroup getPortGroup(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getPortGroupTemplate(), ID_TOKEN, id);
        DtoPortGroup portGroup = resource.get(uri, null, DtoPortGroup.class,
                APPLICATION_PORTGROUP_JSON());
        return new PortGroup(resource, null, portGroup);
    }

    public Route getRoute(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getRouteTemplate(), ID_TOKEN, id);
        DtoRoute route = resource.get(uri, null, DtoRoute.class,
                APPLICATION_ROUTE_JSON());
        return new Route(resource, null, route);
    }

    public Router getRouter(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getRouterTemplate(), ID_TOKEN, id);
        DtoRouter router = resource.get(uri, null, DtoRouter.class,
                APPLICATION_ROUTER_JSON_V3());
        return new Router(resource, null, router);
    }

    public Rule getRule(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getRuleTemplate(), ID_TOKEN, id);
        DtoRule rule = resource.get(uri, null, DtoRule.class,
                APPLICATION_RULE_JSON_V2());
        return new Rule(resource, null, rule);
    }

    public RuleChain getRuleChain(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getChainTemplate(), ID_TOKEN, id);
        DtoRuleChain chain = resource.get(uri, null, DtoRuleChain.class,
                APPLICATION_CHAIN_JSON());
        return new RuleChain(resource, null, chain);
    }

    public Tenant getTenant(String id) {
        URI uri = createUriFromTemplate(
                principalDto.getTenantTemplate(), ID_TOKEN, id);
        return new Tenant(
            resource, null,
            resource.get(uri, null,
                         org.midonet.cluster.rest_api.models.Tenant.class,
                         APPLICATION_TENANT_JSON_V2())
        );
    }

    public SystemState getSystemState() {
        URI uri = principalDto.getSystemState();
        DtoSystemState systemState = resource.get(uri, null,
                DtoSystemState.class,
                APPLICATION_SYSTEM_STATE_JSON_V2());
        return new SystemState(resource, null, systemState);
    }

    public TunnelZone getTunnelZone(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getTunnelZoneTemplate(), ID_TOKEN,
                id);
        DtoTunnelZone tunnelZone = resource.get(uri, null, DtoTunnelZone.class,
                APPLICATION_TUNNEL_ZONE_JSON());
        return new TunnelZone(resource, null, tunnelZone,
                APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON());
    }
}
