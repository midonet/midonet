package com.midokura.midolman.mgmt.rest_api.v1;

import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;

import java.net.URI;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.junit.Test;
import static org.junit.Assert.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.rest_api.v1.resources.TenantResource;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.GrizzlyTestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;

public class MainTest extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(MainTest.class);

    public MainTest() {
        super(new WebAppDescriptor.Builder(
                  "com.midokura.midolman.mgmt.rest_api.v1.resources")
              .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
              .initParam("com.sun.jersey.spi.container.ContainerRequestFilters",
                         "com.midokura.midolman.mgmt.auth.MockAuthFilter")
              .contextParam("zookeeper-root", "/mock/midolman")
              .contextParam("zookeeper-mgmt-root", "/mock/midolman-mgmt")
              .contextPath("context").build());
    }

    @Test
    public void test() {
        WebResource resource;
        ClientResponse response;

        // Init directory.
        resource = resource().path("admin/init");
        response = resource.type(
            MediaType.APPLICATION_JSON).post(ClientResponse.class);

        // Add the tenant
        Tenant tenant = new Tenant();
        tenant.setId("tenant1");

        resource = resource().path("tenants");
        response = resource.type(
            MediaType.APPLICATION_JSON).post(ClientResponse.class, tenant);
        URI tenantURI = response.getLocation();
        log.debug("tanant location: {}", tenantURI);
        
        // Add a router.
        Router router = new Router();
        String routerName = "router1";
        router.setName(routerName);
        resource = resource().uri(
            UriBuilder.fromUri(tenantURI).path("routers").build());
        response = resource.type(
            MediaType.APPLICATION_JSON).post(ClientResponse.class, router);
        URI routerURI = response.getLocation();
        log.debug("router location: {}", routerURI);

        // Get the router.
        resource = resource().uri(routerURI);
        router = resource.type(MediaType.APPLICATION_JSON).get(Router.class);
        log.debug("router name: " + router.getName());
        assertEquals(router.getName(), routerName);
    }

    @Override
    protected TestContainerFactory getTestContainerFactory() {
        return new GrizzlyWebTestContainerFactory();
    }
}
