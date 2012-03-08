/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgeRouterPort;
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
import static org.junit.Assert.fail;

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

        response = resource().uri(testRouterUri).type(APPLICATION_ROUTER_JSON)
                .put(ClientResponse.class, router);
        log.debug("status: {}", response.getStatus());
        assertEquals(200, response.getStatus());

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

    @Test
    public void testLinkAndGetAndDeleteLink() {
        // Link routers.
        log.debug("linking router {} with {}", testRouterUri, anotherRouterUri);
        UUID anotherRouterId = FuncTest.getUuidFromLocation(anotherRouterUri);
        DtoRouter router = resource().uri(testRouterUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        response = resource().uri(router.getPeerRouters())
                .type(APPLICATION_PORT_JSON).post(ClientResponse.class,
                "{\"networkAddress\": \"10.0.0.0\", " +
                "\"networkLength\": 24, \"portAddress\": \"10.0.0.1\", " +
                "\"peerPortAddress\": \"10.0.0.2\",\"peerRouterId\": " +
                "\"" + anotherRouterId + "\"}");
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());

        URI routerRouterUri = response.getLocation();

        // GET the link
        response = resource().uri(routerRouterUri)
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        DtoPeerRouterLink link = response.getEntity(DtoPeerRouterLink.class);
        log.debug("portId: {}", link.getPortId());
        log.debug("peerPortId: {}", link.getPeerPortId());
        log.debug("status: {}", response.getStatus());
        assertEquals(200, response.getStatus());

        // Delete the link
        response = resource().path("ports/" + link.getPortId())
                .type(APPLICATION_PORT_JSON).get(ClientResponse.class);
        body = response.getEntity(String.class);
        log.debug("body: {}", body);
        // Delete the link
        response = resource().uri(routerRouterUri).type(APPLICATION_PORT_JSON)
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }

    @Test
    public void testLink() {
        DtoRouter router = resource().uri(testRouterUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        response = resource().uri(router.getPeerRouters())
                .type(APPLICATION_ROUTER_LINK_COLLECTION_JSON)
                .get(ClientResponse.class);
        String body = response.getEntity(String.class);

        assertEquals(200, response.getStatus());
        log.debug("body: {}", body);
    }

    @Test
    public void testLinkBridge() {
        // Create a bridge
        DtoBridge bridge1 = new DtoBridge();
        bridge1.setName("TEST-BRIDGE1");
        response = resource().path("tenants/" + testTenantName + "/bridges")
                .type(APPLICATION_BRIDGE_JSON)
                .post(ClientResponse.class, bridge1);
        assertEquals(201, response.getStatus());
        bridge1 = resource().uri(response.getLocation())
                .type(APPLICATION_BRIDGE_JSON).get(DtoBridge.class);

        // Link the router and bridge
        DtoBridgeRouterPort port = new DtoBridgeRouterPort();
        port.setBridgeId(bridge1.getId());
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        DtoRouter router1 = resource().uri(testRouterUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        response = resource().uri(router1.getBridges())
                .type(APPLICATION_PORT_JSON).post(ClientResponse.class, port);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        DtoBridgeRouterLink link1 =
                response.getEntity(DtoBridgeRouterLink.class);
        log.debug("bridgePortId: {}", link1.getBridgePortId());
        log.debug("routerPortId: {}", link1.getRouterPortId());
        assertEquals(response.getLocation(), link1.getUri());
        assertEquals(bridge1.getId(), link1.getBridgeId());
        assertEquals(router1.getId(), link1.getRouterId());

        // Now GET the link
        response = resource().uri(link1.getUri())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        assertEquals(link1, response.getEntity(DtoBridgeRouterLink.class));

        // Verify that you get the identical link by querying the bridge.
        response = resource().uri(UriBuilder.fromUri(bridge1.getRouters())
                .path(router1.getId().toString()).build())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        assertEquals(link1, response.getEntity(DtoBridgeRouterLink.class));

        // Create another bridge and link it to the same router
        DtoBridge bridge2 = new DtoBridge();
        bridge2.setName("TEST-BRIDGE2");
        response = resource().path("tenants/" + testTenantName + "/bridges")
                .type(APPLICATION_BRIDGE_JSON)
                .post(ClientResponse.class, bridge2);
        assertEquals(201, response.getStatus());
        bridge2 = resource().uri(response.getLocation())
                .type(APPLICATION_BRIDGE_JSON).get(DtoBridge.class);

        // Link the router and bridge
        port.setBridgeId(bridge2.getId());
        port.setNetworkAddress("10.0.1.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.1.1");
        response = resource().uri(router1.getBridges())
                .type(APPLICATION_PORT_JSON).post(ClientResponse.class, port);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        DtoBridgeRouterLink link2 =
                response.getEntity(DtoBridgeRouterLink.class);
        log.debug("bridgePortId: {}", link2.getBridgePortId());
        log.debug("routerPortId: {}", link2.getRouterPortId());
        assertEquals(response.getLocation(), link2.getUri());
        assertEquals(bridge2.getId(), link2.getBridgeId());
        assertEquals(router1.getId(), link2.getRouterId());

        // Verify that you get the identical link by querying the bridge.
        response = resource().uri(UriBuilder.fromUri(bridge2.getRouters())
                .path(router1.getId().toString()).build())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        assertEquals(link2, response.getEntity(DtoBridgeRouterLink.class));

        // Now try to list the bridge links of the router.
        response = resource().uri(router1.getBridges())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoBridgeRouterLink[] links =
                response.getEntity(DtoBridgeRouterLink[].class);
        assertEquals(2, links.length);
        if (links[0].getBridgeId().equals(bridge1.getId())) {
            assertEquals(links[0], link1);
            assertEquals(links[1], link2);
        }
        else if (links[0].getBridgeId().equals(bridge2.getId())) {
                assertEquals(links[0], link2);
                assertEquals(links[1], link1);
        }
        else fail("The list of bridge links contains unexpected bridge IDs");

        // Now add a link from the other router to bridge1
        port.setBridgeId(bridge1.getId());
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.254");
        DtoRouter router2 = resource().uri(anotherRouterUri)
                .type(APPLICATION_ROUTER_JSON).get(DtoRouter.class);
        response = resource().uri(router2.getBridges())
                .type(APPLICATION_PORT_JSON).post(ClientResponse.class, port);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        DtoBridgeRouterLink link3 =
                response.getEntity(DtoBridgeRouterLink.class);
        log.debug("bridgePortId: {}", link3.getBridgePortId());
        log.debug("routerPortId: {}", link3.getRouterPortId());
        assertEquals(response.getLocation(), link3.getUri());
        assertEquals(bridge1.getId(), link3.getBridgeId());
        assertEquals(router2.getId(), link3.getRouterId());

        // Now list the routers that link to bridge1
        response = resource().uri(bridge1.getRouters())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        links = response.getEntity(DtoBridgeRouterLink[].class);
        assertEquals(2, links.length);
        if (links[0].getRouterId().equals(router1.getId())) {
            assertEquals(links[0], link1);
            assertEquals(links[1], link3);
        }
        else if (links[0].getRouterId().equals(router2.getId())) {
            assertEquals(links[0], link3);
            assertEquals(links[1], link1);
        }
        else fail("The list of bridge links contains unexpected router IDs");

        // Now delete link1 - the link between router1 and bridge1
        // First show that trying to delete either of the ports gives error.
        response = resource().path("ports/" + link1.getRouterPortId())
                .type(APPLICATION_PORT_JSON).delete(ClientResponse.class);
        log.debug("body: {}", response.getEntity(String.class));
        assertEquals(500, response.getStatus());
        response = resource().path("ports/" + link1.getBridgePortId())
                .type(APPLICATION_PORT_JSON).delete(ClientResponse.class);
        log.debug("body: {}", response.getEntity(String.class));
        assertEquals(500, response.getStatus());

        // Delete the link
        response = resource().uri(link1.getUri())
                .type(APPLICATION_PORT_JSON).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());

        // List the routers that link to bridge1 - there should only be rotuer2
        response = resource().uri(bridge1.getRouters())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        links = response.getEntity(DtoBridgeRouterLink[].class);
        assertEquals(1, links.length);
        assertEquals(links[0], link3);
        // List the bridges that link to router1 - there should only be bridge2
        response = resource().uri(router1.getBridges())
                .type(APPLICATION_ROUTER_LINK_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        links = response.getEntity(DtoBridgeRouterLink[].class);
        assertEquals(1, links.length);
        assertEquals(links[0], link2);
    }

}