/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestRouter extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestRouter.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";
    private final String anotherRouterName = "ANOTHER-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private URI anotherRouterUri;

    public TestRouter() {
        super(FuncTest.appDesc);
    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() {
        DtoTenant tenant = new DtoTenant();
        tenant.setId(testTenantName);

        resource = resource().path("tenants");
        response = resource.type(APPLICATION_TENANT_JSON).post(
                ClientResponse.class, tenant);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        assertTrue(response.getLocation().toString()
                .endsWith("tenants/" + testTenantName));

        DtoRouter router = new DtoRouter();
        router.setName(testRouterName);
        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        testRouterUri = response.getLocation();

        // create another router here for testing link related APIs
        router = new DtoRouter();
        router.setName(anotherRouterName);
        response = resource().path("tenants/" + testTenantName + "/routers")
                .type(APPLICATION_ROUTER_JSON)
                .post(ClientResponse.class, router);
        anotherRouterUri = response.getLocation();
        log.debug("location {}", anotherRouterUri);
    }

    @Test
    public void testCreateWithEmptyBody() {
        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, "{}");
        String body = response.getEntity(String.class);

        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        log.debug("body: {}", body);

        String idString = response.getLocation().toString();
        idString = idString.substring(
                idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID " +
                    "form. {}" + e);
        }
    }

    @Test
    public void testCreateWithGivenIdAndNameAndGet() {
        UUID id = UUID.randomUUID();
        DtoRouter router = new DtoRouter();
        router.setName("TEST-CREATE-ROUTER");
        router.setId(id);

        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, router);
        String body = response.getEntity(String.class);
        URI routerUri = response.getLocation();

        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", routerUri);
        log.debug("body: {}", body);

        String idString = response.getLocation().toString();
        idString = idString.substring(
                idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID " +
                    "form. {}" + e);
        }

        router = resource().uri(routerUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        assertEquals("TEST-CREATE-ROUTER", router.getName());
        assertEquals(id, router.getId());
        log.debug("bridge port: {}", router.getPorts());
        log.debug("bridge uri: {}", router.getUri());
    }

    @Test
    public void testGet() {
        DtoRouter router = resource().uri(testRouterUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        log.debug("testGet name:  {}", router.getName());
        log.debug("testGet id:  {}", router.getId());
        log.debug("testGet tenantId: {}", router.getTenantId());
        log.debug("testGet ports: {}", router.getPorts());
        log.debug("testGet chains: {}", router.getUri());
        log.debug("testGet peerPorts: {}", router.getPeerPorts());
        log.debug("testGet uri: {}", router.getUri());

        assertEquals(testRouterName, router.getName());
        assertEquals(testRouterUri, router.getUri());
        assertEquals(testTenantName, router.getTenantId());
    }

    @Test
    public void testUpdate() {
        final String newName = "NEW-ROUTER-NAME";
        DtoRouter router = new DtoRouter();
        router.setName(newName);

        response = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON)
                .put(ClientResponse.class, router);
        log.debug("status: {}", response.getStatus());
        assertEquals(204, response.getStatus());

        router = resource().uri(testRouterUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        log.debug("name: {}", router.getName());
        assertEquals(newName, router.getName());
    }

    @Test
    public void testDelete() {
        response = resource().uri(testRouterUri).delete(ClientResponse.class);
        log.debug("status: {}", response.getStatus());
        assertEquals(204, response.getStatus());
    }

    @Test
    public void testList() {
        response = resource().path("tenants/" + testTenantName + "/routers")
                .get(ClientResponse.class);
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("body: {}", body);

        assertEquals(200, response.getStatus());
    }
}