package com.midokura.midonet.client;

import java.net.URI;

import javax.ws.rs.core.MultivaluedMap;

import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.resource.Application;
import com.midokura.midonet.client.resource.Bridge;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.PortGroup;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midonet.client.resource.RuleChain;


/**
 * Midonet API wrapping class.
 */
public class MidonetMgmt {

    private static final String DEFAULT_MIDONET_URI =
        "http://localhost:8080/midolmanj-mgmt";

    private final URI midonetUri;
    private final WebResource resource;
    private Application application;

    public MidonetMgmt(String midonetUriStr) {
        this.midonetUri = URI.create(midonetUriStr);
        resource = new WebResource(midonetUri);
    }

    public MidonetMgmt() {
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
     * Gets Bridges.
     *
     * @return collection of bridge
     */
    public ResourceCollection<Bridge> getBridges(MultivaluedMap queryParams) {
        ensureApplication();
        return application.getBridges(queryParams);
    }

    /**
     * Gets Routers.
     *
     * @return collection of router
     */
    public ResourceCollection<Router> getRouters(MultivaluedMap queryParams) {
        ensureApplication();
        return application.getRouters(queryParams);
    }

    /**
     * Gets Chains.
     *
     * @return collection of chain
     */
    public ResourceCollection<RuleChain> getChains(MultivaluedMap queryParams) {
        ensureApplication();
        return application.getChains(queryParams);
    }

    /**
     * Gets PortGroups.
     *
     * @return collection of port group
     */
    public ResourceCollection<PortGroup> getPortGroups(
        MultivaluedMap queryParams) {
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

    private void ensureApplication() {
        if (application == null) {
            DtoApplication dtoApplication = resource
                .get("",
                     DtoApplication.class,
                     VendorMediaType.APPLICATION_JSON);
            application = new Application(resource, dtoApplication);
        }
    }
}
