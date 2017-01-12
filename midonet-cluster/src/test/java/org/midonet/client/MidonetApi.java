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
import org.midonet.client.resource.Application;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.Port;
import org.midonet.client.resource.PortGroup;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RuleChain;
import org.midonet.client.resource.SystemState;
import org.midonet.client.resource.Tenant;
import org.midonet.client.resource.TunnelZone;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;


/**
 * Midonet API wrapping class.
 */
public class MidonetApi {

    private static final String DEFAULT_MIDONET_URI =
        "http://localhost:8181/midonet-api";

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
     * Returns SystemState object
     *
     * @return SystemState
     */
    public SystemState getSystemState() {
        ensureApplication();
        return application.getSystemState();
    }

    private void ensureApplication() {
        if (application == null) {
            DtoApplication dtoApplication = resource.get("",
                     DtoApplication.class,
                     MidonetMediaTypes.APPLICATION_JSON_V5());
            application = new Application(resource, dtoApplication,
                     MidonetMediaTypes.APPLICATION_JSON_V5());
        }
    }
}
