/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.net.URI;

import javax.ws.rs.core.MultivaluedMap;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoGreTunnelZone;
import com.midokura.midonet.client.dto.DtoCapwapTunnelZone;
import com.midokura.midonet.client.dto.DtoHost;
import com.midokura.midonet.client.dto.DtoPortGroup;
import com.midokura.midonet.client.dto.DtoRouter;
import com.midokura.midonet.client.dto.DtoRuleChain;
import com.midokura.midonet.client.dto.DtoTunnelZone;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/15/12
 * Time: 12:31 PM
 */
public class Application extends ResourceBase<Application, DtoApplication> {

    DtoApplication app;
    WebResource resource;

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
     * Gets bridges.
     * i
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
                VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON);
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
                VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON);
    }

}
