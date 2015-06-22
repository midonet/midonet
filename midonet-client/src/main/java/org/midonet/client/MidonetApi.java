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

package org.midonet.client;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.dto.DtoApplication;
import org.midonet.client.resource.AdRoute;
import org.midonet.client.resource.Application;
import org.midonet.client.resource.Bgp;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostVersion;
import org.midonet.client.resource.Port;
import org.midonet.client.resource.PortGroup;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.Route;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.Rule;
import org.midonet.client.resource.RuleChain;
import org.midonet.client.resource.SystemState;
import org.midonet.client.resource.Tenant;
import org.midonet.client.resource.TunnelZone;
import org.midonet.client.resource.WriteVersion;
import org.midonet.cluster.rest_api.VendorMediaType;


/**
 * Midonet API wrapping class.
 */
public class MidonetApi {

    private static final String DEFAULT_MIDONET_URI =
        "http://localhost:8080/midonet-api";

    private final WebResource resource;
    private Application application;

    public MidonetApi(String midonetUriStr) {
        URI midonetUri = URI.create(midonetUriStr);
        resource = new WebResource(midonetUri);
    }

    public MidonetApi() {
        this(DEFAULT_MIDONET_URI);
    }

    public void enableLogging() {
        resource.enableLogging();
    }

    public void disableLogging() {
        resource.disableLogging();
    }

    /**
     * Adds a Bridge.
     *
     * @return a bridge resource
     */
    public Bridge addBridge() {
        ensureApplication();
        return application.addBridge();
    }

    /**
     * Adds a Router.
     *
     * @return a router resource
     */
    public Router addRouter() {
        ensureApplication();
        return application.addRouter();
    }

    /**
     * Adds a Chain.
     *
     * @return chain resource
     */
    public RuleChain addChain() {
        ensureApplication();
        return application.addChain();
    }

    /**
     * Adds a PortGroup.
     *
     * @return port group resource
     */
    public PortGroup addPortGroup() {
        ensureApplication();
        return application.addPortGroup();
    }

    /**
     * Adds a GRE tunnel zone
     *
     * @return gre tunnel zone resource
     */
    public TunnelZone addGreTunnelZone() {
        ensureApplication();
        return application.addGreTunnelZone();
    }

    /**
     * Gets Bridges.
     *
     * @return collection of bridge
     */
    public ResourceCollection<Bridge> getBridges(
            MultivaluedMap<String,String> queryParams) {
        ensureApplication();
        return application.getBridges(queryParams);
    }

    /**
     * Gets Routers.
     *
     * @return collection of router
     */
    public ResourceCollection<Router> getRouters(
            MultivaluedMap<String,String> queryParams) {
        ensureApplication();
        return application.getRouters(queryParams);
    }

    /**
     * Gets Chains.
     *
     * @return collection of chain
     */
    public ResourceCollection<RuleChain> getChains(
            MultivaluedMap<String,String> queryParams) {
        ensureApplication();
        return application.getChains(queryParams);
    }

    /**
     * Gets PortGroups.
     *
     * @return collection of port group
     */
    public ResourceCollection<PortGroup> getPortGroups(
            MultivaluedMap<String,String> queryParams) {
        ensureApplication();
        return application.getPortGroups(queryParams);
    }

    /**
     * Gets Hosts
     *
     * @return collection of host
     */
    public ResourceCollection<Host> getHosts() {
        ensureApplication();
        return application.getHosts(null);
    }

    /**
     * Gets Tenants
     *
     * @return collection of tenants
     */
    public ResourceCollection<Tenant> getTenants() {
        ensureApplication();
        return application.getTenants(null);
    }

    /**
     * Gets Tunnel Zones
     *
     * @return collection of tunnel zone
     */
    public ResourceCollection<TunnelZone> getTunnelZones() {
        ensureApplication();
        return application.getTunnelZones(null);
    }

    /**
     * Returns an ad route
     *
     * @param id ID of ad route
     * @return AdRoute
     */
    public AdRoute getAdRoute(UUID id) {
        ensureApplication();
        return application.getAdRoute(id);
    }

    /**
     * Returns BGP object
     *
     * @param id ID of BGP
     * @return BGP
     */
    public Bgp getBgp(UUID id) {
        ensureApplication();
        return application.getBgp(id);
    }

    /**
     * Returns Bridge object
     *
     * @param id ID of bridge
     * @return Bridge
     */
    public Bridge getBridge(UUID id) {
        ensureApplication();
        return application.getBridge(id);
    }

    /**
     * Returns RuleChain object
     *
     * @param id ID of chain
     * @return RuleChain
     */
    public RuleChain getChain(UUID id) {
        ensureApplication();
        return application.getRuleChain(id);
    }

    /**
     * Returns Host object
     *
     * @param id ID of host
     * @return Host
     */
    public Host getHost(UUID id) {
        ensureApplication();
        return application.getHost(id);
    }

    /**
     * Returns Port object
     *
     * @param id ID of port
     * @return Port
     */
    public Port<?,?> getPort(UUID id) {
        ensureApplication();
        return application.getPort(id);
    }

    /**
     * Returns PortGroup object
     *
     * @param id ID of port group
     * @return PortGroup
     * */
    public PortGroup getPortGroup(UUID id) {
        ensureApplication();
        return application.getPortGroup(id);
    }

    /**
     * Returns Route object
     *
     * @param id ID of route
     * @return Route
     * */
    public Route getRoute(UUID id) {
        ensureApplication();
        return application.getRoute(id);
    }

    /**
     * Returns Router object
     *
     * @param id ID of router
     * @return Router
     * */
    public Router getRouter(UUID id) {
        ensureApplication();
        return application.getRouter(id);
    }

    /**
     * Returns Rule object
     *
     * @param id ID of rule
     * @return Rule
     */
    public Rule getRule(UUID id) {
        ensureApplication();
        return application.getRule(id);
    }

    /**
     * Returns Tenant object
     *
     * @param id ID of tenant
     * @return Tenant
     */
    public Tenant getTenant(String id) {
        ensureApplication();
        return application.getTenant(id);
    }

    /**
     * Returns SystemState object
     *
     * @return SystemState
     */
    public SystemState getSystemState() {
        ensureApplication();
        return application.getSystemState();
    }

    /**
     * Returns WriteVersion object
     *
     * @return WriteVersion
     */
    public WriteVersion getWriteVersion() {
        ensureApplication();
        return application.getWriteVersion();
    }

    /**
     * Returns List of Host Versions
     */
    public ResourceCollection<HostVersion> getHostVersions() {
        ensureApplication();
        return application.getHostVersions();
    }

    /**
     * Returns TunnelZone object
     *
     * @param id ID of tunnel zone
     * @return TunnelZone
     */
    public TunnelZone getTunnelZone(UUID id) {
        ensureApplication();
        return application.getTunnelZone(id);
    }

    private void ensureApplication() {
        if (application == null) {
            DtoApplication dtoApplication = resource.get("",
                     DtoApplication.class,
                     VendorMediaType.APPLICATION_JSON_V5);
            application = new Application(resource, dtoApplication,
                     VendorMediaType.APPLICATION_JSON_V5);
        }
    }
}
