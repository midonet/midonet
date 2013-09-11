/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.UUID;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/15/12
 * Time: 12:31 PM
 */
public class Application extends ResourceBase<Application, DtoApplication> {

    DtoApplication app;
    WebResource resource;
    private static String ID_TOKEN = "{id}";

    public Application(WebResource resource, DtoApplication app) {
        super(resource, null, app, VendorMediaType.APPLICATION_JSON);
        this.app = app;
        this.resource = resource;
    }

    /**
     * Returns URI of the REST API for this resource
     *
     * @return uri of the resource
     */
    @Override
    public URI getUri() {
        return app.getUri();
    }

    /**
     * Returns version of the application
     *
     * @return version
     */
    public String getVersion() {
        get();
        return app.getVersion();
    }

    /**
     * Gets tenants.
     *
     * @return Collection of tenants
     */
    public ResourceCollection<Tenant> getTenants(MultivaluedMap queryParams) {
        return getChildResources(principalDto.getTenants(),
                queryParams,
                VendorMediaType
                        .APPLICATION_TENANT_COLLECTION_JSON,
                Tenant.class, DtoTenant.class);
    }

    /**
     * Gets vlan bridges.
     *
     * @return Collection of vlan bridges
     */
    public ResourceCollection<VlanBridge> getVlanBridges(MultivaluedMap queryParams) {
        return getChildResources(principalDto.getVlanBridges(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_VLAN_BRIDGE_COLLECTION_JSON,
                                 VlanBridge.class, DtoVlanBridge.class);
    }

    /**
     * Gets bridges.
     *
     * @return Collection of bridges
     */
    public ResourceCollection<Bridge> getBridges(MultivaluedMap queryParams) {
        return getChildResources(principalDto.getBridges(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_BRIDGE_COLLECTION_JSON,
                                 Bridge.class, DtoBridge.class);
    }

    /**
     * Gets routers.
     *
     * @return collection of routers
     */
    public ResourceCollection<Router> getRouters(MultivaluedMap queryParams) {
        return getChildResources(principalDto.getRouters(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_ROUTER_COLLECTION_JSON,
                                 Router.class, DtoRouter.class);
    }

    /**
     * Gets chains
     *
     * @return collection of chains
     */
    public ResourceCollection<RuleChain> getChains(MultivaluedMap queryParams) {
        return getChildResources(principalDto.getChains(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_CHAIN_COLLECTION_JSON,
                                 RuleChain.class, DtoRuleChain.class);
    }

    /**
     * Gets port groups.
     *
     * @return collection of port groups
     */
    public ResourceCollection<PortGroup> getPortGroups(
        MultivaluedMap queryParams) {
        return getChildResources(principalDto.getPortGroups(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_PORTGROUP_COLLECTION_JSON,
                                 PortGroup.class, DtoPortGroup.class);
    }

    /**
     * Gets hosts.
     *
     * @return collection host
     */
    public ResourceCollection<Host> getHosts(
        MultivaluedMap queryParams) {
        return getChildResources(principalDto.getHosts(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_HOST_COLLECTION_JSON,
                                 Host.class, DtoHost.class);
    }

    /**
     * Gets Tunnel Zones.
     *
     * @return collection of tunnel zones
     */
    public ResourceCollection<TunnelZone> getTunnelZones(
        MultivaluedMap queryParams) {
        return getChildResources(principalDto.getTunnelZones(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                                 TunnelZone.class, DtoTunnelZone.class);
    }

    /**
     * Adds a vlan bridge.
     *
     * @return new VlanBridge resource
     */
    public VlanBridge addVlanBridge() {
        return new VlanBridge(resource, principalDto.getVlanBridges(),
                          new DtoVlanBridge());
    }

    /**
     * Adds a bridge.
     *
     * @return new Bridge resource
     */
    public Bridge addBridge() {
        return new Bridge(resource, principalDto.getBridges(),
                          new DtoBridge());
    }

    /**
     * Adds a router.
     *
     * @return new Router() resource
     */
    public Router addRouter() {
        return new Router(resource, principalDto.getRouters(),
                          new DtoRouter());
    }

    /**
     * Adds a chain.
     *
     * @return new Chain() resource
     */
    public RuleChain addChain() {
        return new RuleChain(resource, principalDto.getChains(),
                             new DtoRuleChain());
    }

    /**
     * Adds a port group.
     *
     * @return new PortGroup() resource.
     */
    public PortGroup addPortGroup() {
        return new PortGroup(resource, principalDto.getPortGroups(),
                             new DtoPortGroup());
    }

    /**
     * Adds a tunnel zone
     *
     * @return new gre tunnel zone.
     */
    public TunnelZone<DtoGreTunnelZone> addGreTunnelZone() {
        return new TunnelZone<DtoGreTunnelZone>(resource,
                principalDto.getTunnelZones(),
                new DtoGreTunnelZone(),
                VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                VendorMediaType
                        .APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON);
    }

    /**
     * Adds a tunnel zone
     *
     * @return new capwap tunnel zone.
     */
    public TunnelZone<DtoCapwapTunnelZone> addCapwapTunnelZone() {
        return new TunnelZone<DtoCapwapTunnelZone>(resource,
                principalDto.getTunnelZones(),
                new DtoCapwapTunnelZone(),
                VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON,
                VendorMediaType
                        .APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON);
    }

    /**
     * Returns an ad route
     *
     * @param id ID of ad route
     * @return AdRoute
     */
    public AdRoute getAdRoute(UUID id) {
        URI uri = createUriFromTemplate(
                app.getAdRouteTemplate(), ID_TOKEN, id);
        DtoAdRoute adRoute = resource.get(uri, null, DtoAdRoute.class,
                VendorMediaType.APPLICATION_AD_ROUTE_JSON);
        return new AdRoute(resource, null, adRoute);
    }

    /**
     * Returns BGP object
     *
     * @param id ID of BGP
     * @return BGP
     */
    public Bgp getBgp(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getBgpTemplate(), ID_TOKEN, id);
        DtoBgp bgp = resource.get(uri, null, DtoBgp.class,
                VendorMediaType.APPLICATION_BGP_JSON);
        return new Bgp(resource, null, bgp);
    }

    /**
     * Returns Vlan Bridge object
     *
     * @param id ID of bridge
     * @return Bridge
     */
    public VlanBridge getVlanBridge(UUID id) {
        URI uri = createUriFromTemplate(
            principalDto.getVlanBridgeTemplate(), ID_TOKEN, id);
        DtoVlanBridge bridge = resource.get(uri, null, DtoVlanBridge.class,
                                        VendorMediaType.APPLICATION_VLAN_BRIDGE_JSON);
        return new VlanBridge(resource, null, bridge);
    }

    /**
     * Returns Bridge object
     *
     * @param id ID of bridge
     * @return Bridge
     */
    public Bridge getBridge(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getBridgeTemplate(), ID_TOKEN, id);
        DtoBridge bridge = resource.get(uri, null, DtoBridge.class,
                VendorMediaType.APPLICATION_BRIDGE_JSON);
        return new Bridge(resource, null, bridge);
    }

    /**
     * Returns Host object
     *
     * @param id ID of host
     * @return Host
     */
    public Host getHost(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getHostTemplate(), ID_TOKEN, id);
        DtoHost host = resource.get(uri, null, DtoHost.class,
                VendorMediaType.APPLICATION_HOST_JSON);
        return new Host(resource, null, host);
    }

    /**
     * Returns Port object
     *
     * @param id ID of port
     * @return Port
     */
    public Port getPort(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getPortTemplate(), ID_TOKEN, id);
        DtoPort port = resource.get(uri, null, DtoPort.class,
                VendorMediaType.APPLICATION_PORT_JSON);
        if (port instanceof DtoBridgePort) {
            return new BridgePort(resource, null, (DtoBridgePort) port);
        } else if (port instanceof  DtoRouterPort) {
            return new RouterPort(resource, null, (DtoRouterPort) port);
        } else if (port instanceof  DtoVlanBridgeInteriorPort) {
            return new VlanBridgeInteriorPort(resource, null, (DtoVlanBridgeInteriorPort) port);
        } else if (port instanceof  DtoVlanBridgeTrunkPort) {
            return new VlanBridgeTrunkPort(resource, null, (DtoVlanBridgeTrunkPort) port);
        } else {
            throw new IllegalArgumentException(
                    "No port with ID (" + id + ") exists.");
        }
    }

    /**
     * Returns PortGroup object
     *
     * @param id ID of port group
     * @return PortGroup
     * */
    public PortGroup getPortGroup(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getPortGroupTemplate(), ID_TOKEN, id);
        DtoPortGroup portGroup = resource.get(uri, null, DtoPortGroup.class,
                VendorMediaType.APPLICATION_PORTGROUP_JSON);
        return new PortGroup(resource, null, portGroup);
    }

    /**
     * Returns Route object
     *
     * @param id ID of route
     * @return Route
     * */
    public Route getRoute(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getRouteTemplate(), ID_TOKEN, id);
        DtoRoute route = resource.get(uri, null, DtoRoute.class,
                VendorMediaType.APPLICATION_ROUTE_JSON);
        return new Route(resource, null, route);
    }

    /**
     * Returns Router object
     *
     * @param id ID of router
     * @return Router
     * */
    public Router getRouter(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getRouterTemplate(), ID_TOKEN, id);
        DtoRouter router = resource.get(uri, null, DtoRouter.class,
                VendorMediaType.APPLICATION_ROUTER_JSON);
        return new Router(resource, null, router);
    }

    /**
     * Returns Rule object
     *
     * @param id ID of rule
     * @return Rule
     */
    public Rule getRule(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getRuleTemplate(), ID_TOKEN, id);
        DtoRule rule = resource.get(uri, null, DtoRule.class,
                VendorMediaType.APPLICATION_RULE_JSON);
        return new Rule(resource, null, rule);
    }

    /**
     * Returns RuleChain object
     *
     * @param id ID of chain
     * @return RuleChain
     */
    public RuleChain getRuleChain(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getChainTemplate(), ID_TOKEN, id);
        DtoRuleChain chain = resource.get(uri, null, DtoRuleChain.class,
                VendorMediaType.APPLICATION_CHAIN_JSON);
        return new RuleChain(resource, null, chain);
    }

    /**
     * Returns Tenant object
     *
     * @param id ID of tenant
     * @return Tenant
     */
    public Tenant getTenant(String id) {
        URI uri = createUriFromTemplate(
                principalDto.getTenantTemplate(), ID_TOKEN, id);
        DtoTenant tenant = resource.get(uri, null, DtoTenant.class,
                VendorMediaType.APPLICATION_TENANT_JSON);
        return new Tenant(resource, null, tenant);
    }

    /**
     * Gets the WriteVersion object.
     *
     * @return WriteVersion
     */
    public WriteVersion getWriteVersion() {
        URI uri = principalDto.getWriteVersion();
        DtoWriteVersion writeVersion = resource.get(uri, null,
                DtoWriteVersion.class,
                VendorMediaType.APPLICATION_WRITE_VERSION_JSON);
        return new WriteVersion(resource, null, writeVersion);
    }

    /**
     * Gets the SystemState object.
     *
     * @return SystemState
     */
    public SystemState getSystemState() {
        URI uri = principalDto.getSystemState();
        DtoSystemState systemState = resource.get(uri, null,
                DtoSystemState.class,
                VendorMediaType.APPLICATION_SYSTEM_STATE_JSON);
        return new SystemState(resource, null, systemState);
    }

    /**
     * Gets the HostVersion object.
     *
     * @return SystemState
     */
    public ResourceCollection<HostVersion> getHostVersions() {
        return getChildResources(principalDto.getHostVersions(), null,
                                 VendorMediaType.APPLICATION_HOST_VERSION_JSON,
                                 HostVersion.class, DtoHostVersion.class);
    }

    /**
     * Returns TunnelZone object
     *
     * @param id ID of tunnel zone
     * @return TunnelZone
     */
    public TunnelZone getTunnelZone(UUID id) {
        URI uri = createUriFromTemplate(
                principalDto.getTunnelZoneTemplate(), ID_TOKEN,
                id);
        DtoTunnelZone tunnelZone = resource.get(uri, null, DtoTunnelZone.class,
                VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON);
        if (tunnelZone instanceof DtoGreTunnelZone) {
            return new TunnelZone(resource, null, (DtoGreTunnelZone) tunnelZone,
                    VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    VendorMediaType
                            .APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON);
        } else if (tunnelZone instanceof DtoCapwapTunnelZone) {
            return new TunnelZone(resource, null,
                    (DtoCapwapTunnelZone) tunnelZone,
                    VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON,
                    VendorMediaType
                          .APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON);
        } else if (tunnelZone instanceof DtoIpsecTunnelZone) {
            return new TunnelZone(resource, null,
                    (DtoIpsecTunnelZone) tunnelZone,
                    VendorMediaType.APPLICATION_IPSEC_TUNNEL_ZONE_HOST_JSON,
                    VendorMediaType
                           .APPLICATION_IPSEC_TUNNEL_ZONE_HOST_COLLECTION_JSON);
        } else {
            throw new IllegalArgumentException(
                    "No tunnel zone with ID (" + id + ") exists.");
        }
    }
}
