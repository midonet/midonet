package com.midokura.midolman.mgmt.rest_api.v1;

import static org.junit.Assert.assertEquals;

import java.net.URI;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.mgmt.servlet.ServletListener;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;

public class MainTest extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(MainTest.class);

    public MainTest() {
        super(new WebAppDescriptor.Builder(new String[] {
                "com.midokura.midolman.mgmt.rest_api.resources",
                "com.midokura.midolman.mgmt.data" })
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                        "com.sun.jersey.spi.container.ContainerRequestFilters",
                        "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .contextParam("datastore_service",
                        "com.midokura.midolman.mgmt.data.MockDaoFactory")
                .contextParam("zk_conn_string", "")
                .contextParam("zk_timeout", "0")
                .contextParam("zk_root", "/test/midolman")
                .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
                .contextListenerClass(ServletListener.class)
                .contextPath("/test").build());
    }

    @Test
    public void test() {
        WebResource resource;
        ClientResponse response;

        // Init directory.
        resource = resource().path("admin/init");
        response = resource.type(MediaType.APPLICATION_JSON).post(
                ClientResponse.class);

        // Add the tenant
        Tenant tenant = new Tenant();
        tenant.setId("tenant1");

        resource = resource().path("tenants");
        response = resource.type(MediaType.APPLICATION_JSON).post(
                ClientResponse.class, tenant);
        URI tenantURI = response.getLocation();
        log.debug("tanant location: {}", tenantURI);

        // Add a router.
        Router router = new Router();
        String routerName = "router1";
        router.setName(routerName);
        resource = resource().uri(
                UriBuilder.fromUri(tenantURI).path("routers").build());
        response = resource.type(MediaType.APPLICATION_JSON).post(
                ClientResponse.class, router);
        URI routerURI = response.getLocation();
        log.debug("router location: {}", routerURI);

        // Get the router.
        resource = resource().uri(routerURI);
        router = resource.type(MediaType.APPLICATION_JSON).get(Router.class);
        log.debug("router name: {}", router.getName());
        assertEquals(router.getName(), routerName);

        // Add a materialized router port.
        MaterializedRouterPort port = new MaterializedRouterPort();
        String portAddress = "180.214.47.66";
        port.setNetworkAddress("180.214.47.64");
        port.setNetworkLength(30);
        port.setPortAddress(portAddress);
        port.setLocalNetworkAddress("180.214.47.64");
        port.setLocalNetworkLength(30);
        resource = resource().uri(
            UriBuilder.fromUri(routerURI).path("ports").build());
        log.debug("port JSON {}", port.toString());
        response = resource.type(MediaType.APPLICATION_JSON)
            .post(ClientResponse.class, port);
        URI portURI = response.getLocation();
        log.debug("port location: {}", portURI);

        // Get the port.
        resource = resource().uri(portURI);
        port = resource.type(MediaType.APPLICATION_JSON)
            .get(MaterializedRouterPort.class);
        log.debug("port address: {}", port.getPortAddress());
        assertEquals(port.getPortAddress(), portAddress);

        // Add a VPN to the materialized router port.
        Vpn vpn = new Vpn();
        int vpnPort = 1234;
        vpn.setPort(vpnPort);
        resource = resource().uri(
            UriBuilder.fromUri(portURI).path("vpns").build());
        response = resource.type(MediaType.APPLICATION_JSON)
            .post(ClientResponse.class, vpn);
        URI vpnURI = response.getLocation();
        log.debug("vpn location: {}", vpnURI);

        // Get the port.
        resource = resource().uri(vpnURI);
        vpn = resource.type(MediaType.APPLICATION_JSON).get(Vpn.class);
        log.debug("vpn port: {}", vpn.getPort());
        assertEquals(vpn.getPort(), vpnPort);
    }

    @Override
    protected TestContainerFactory getTestContainerFactory() {
        return new GrizzlyWebTestContainerFactory();
    }
}
