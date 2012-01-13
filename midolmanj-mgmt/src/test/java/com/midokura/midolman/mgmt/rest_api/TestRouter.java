/*
 * @(#)testRouter        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoPeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestRouter extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestRouter.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";
    private final String anotherRouterName = "ANOTHER-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private UUID anotherRouterId;
    DtoRouter router = new DtoRouter();

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
        assertTrue(response.getLocation().toString().endsWith("tenants/" + testTenantName));

        router.setName(testRouterName);
        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        testRouterUri = response.getLocation();

        // create another router here for testing link related APIs
        router.setName(anotherRouterName);
        response = resource().path("tenants/" + testTenantName + "/routers").type(APPLICATION_ROUTER_JSON)
                .post(ClientResponse.class, router);
        log.debug("location {}", response.getLocation());

        anotherRouterId = FuncTest.getUuidFromLocation(response.getLocation());
        log.debug("anotherRouterId UUID {}", anotherRouterId);
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
        idString = idString.substring(idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID form. {}" + e);
        }
    }

    @Test
    public void testCreateWithGivenIdAndNameAndGet() {
        UUID id = UUID.randomUUID();
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
        idString = idString.substring(idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID form. {}" + e);
        }

        router = resource().uri(routerUri).type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        assertEquals("TEST-CREATE-ROUTER", router.getName());
        assertEquals(id, router.getId());
        log.debug("bridge port: {}", router.getPorts());
        log.debug("bridge uri: {}", router.getUri());
    }

    @Test
    public void testGet() {
        router = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        log.debug("testGet name:  {}", router.getName());
        log.debug("testGet id:  {}", router.getId());
        log.debug("testGet tenantId: {}", router.getTenantId());
        log.debug("testGet ports: {}", router.getPorts());
        log.debug("testGet chains: {}", router.getUri());
        log.debug("testGet peerRouters: {}", router.getPeerRouters());
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

        response = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON).put(ClientResponse.class, router);
        log.debug("status: {}", response.getStatus());
        assertEquals(200, response.getStatus());

        router = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
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
        response = resource().path("tenants/" + testTenantName + "/routers").get(ClientResponse.class);
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("body: {}", body);

        assertEquals(200, response.getStatus());
    }

    @Test
    public void testLinkAndGetAndDeleteLink() {
        // Link routers.
        log.debug("linking router {} with {}", testRouterUri, anotherRouterId);
        router = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        response = resource().uri(router.getPeerRouters()).type(APPLICATION_PORT_JSON).post(ClientResponse.class,
              "{\"networkAddress\": \"10.0.0.0\", " +
              "\"networkLength\": 24,  \"portAddress\": \"10.0.0.1\", " +
              "\"peerPortAddress\": \"10.0.0.2\",\"peerRouterId\": " +
              "\"" + anotherRouterId + "\"}");
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());

        URI routerRouterUri = response.getLocation();

        // GET the link
        DtoPeerRouterLink link = new DtoPeerRouterLink();
        response = resource().uri(routerRouterUri).type(APPLICATION_PORT_JSON).get(ClientResponse.class);
        link = response.getEntity(DtoPeerRouterLink.class);
        log.debug("portId: {}", link.getPortId());
        log.debug("peerPortId: {}", link.getPeerPortId());
        log.debug("status: {}", response.getStatus());
        assertEquals(200, response.getStatus());

        // Delete the link
        response = resource().path("ports/" + link.getPortId()).type(APPLICATION_PORT_JSON).get(ClientResponse.class);
        body = response.getEntity(String.class);
        log.debug("body: {}", body);
        // Delete the link
        response = resource().uri(routerRouterUri).type(APPLICATION_PORT_JSON).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }

    @Test
    public void testLink() {
        router = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        response = resource().uri(router.getPeerRouters())
                .type(APPLICATION_ROUTER_LINK_COLLECTION_JSON).get(ClientResponse.class);
        String body = response.getEntity(String.class);

        assertEquals(200, response.getStatus());
        log.debug("body: {}", body);
    }
}