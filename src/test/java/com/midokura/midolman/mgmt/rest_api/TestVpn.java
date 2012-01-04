/*
 * @(#)testVpn        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midolman.mgmt.data.dto.client.DtoVpn;
import com.midokura.midolman.state.VpnZkManager;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestVpn extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestVpn.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";


    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private URI portUri;

    private UUID privatePortId;

    DtoRouter router = new DtoRouter();

    public TestVpn() {
        super(FuncTest.appDesc);
    }

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

        // Create a router.
        router.setName(testRouterName);
        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        testRouterUri = response.getLocation();

        DtoMaterializedRouterPort port = new DtoMaterializedRouterPort();
        String portAddress = "180.214.47.66";
        port.setNetworkAddress("180.214.47.64");
        port.setNetworkLength(30);
        port.setPortAddress(portAddress);
        port.setLocalNetworkAddress("180.214.47.64");
        port.setLocalNetworkLength(30);
        resource = resource().uri(
                UriBuilder.fromUri(testRouterUri).path("ports").build());
        log.debug("port JSON {}", port.toString());

        response = resource.type(APPLICATION_PORT_JSON).post(
                ClientResponse.class, port);
        portUri = response.getLocation();
        log.debug("port location: {}", portUri);

        // Add a materialized router port for private port of VPN.
        port = new DtoMaterializedRouterPort();
        portAddress = "192.168.10.1";
        port.setNetworkAddress("192.168.10.0");
        port.setNetworkLength(30);
        port.setPortAddress(portAddress);
        port.setLocalNetworkAddress("192.168.10.2");
        port.setLocalNetworkLength(30);
        resource = resource().uri(
                UriBuilder.fromUri(testRouterUri).path("ports").build());
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, port);
        log.debug("port JSON {}", port.toString());

        response = resource.type(APPLICATION_PORT_JSON).post(
                ClientResponse.class, port);
        log.debug("port location: {}", response.getLocation());

        privatePortId = FuncTest.getUuidFromLocation(response.getLocation());
    }

    @Test
    public void testCreateGetListDelete() {

        // create a vpn entry
        DtoVpn vpn = new DtoVpn();
        int vpnPort = 1234;
        vpn.setPort(vpnPort);
        vpn.setPrivatePortId(privatePortId);
        vpn.setVpnType(VpnZkManager.VpnType.valueOf("OPENVPN_SERVER"));

        resource = resource().uri(
                UriBuilder.fromUri(portUri).path("vpns").build());
        response = resource.type(APPLICATION_VPN_JSON).post(
                ClientResponse.class, vpn);
        URI vpnUri = response.getLocation();

        log.debug("vpn location: {}", vpnUri);
        log.debug("status {}", response.getLocation());

        // Get the vpn
        resource = resource().uri(vpnUri);
        response = resource.type(APPLICATION_VPN_JSON).get(ClientResponse.class);
        vpn = response.getEntity(DtoVpn.class);
        log.debug("vpn port: {}", vpn.getPort());
        assertEquals(200, response.getStatus());
        assertEquals(vpnPort, vpn.getPort());

        //List vpns
        resource = resource().uri(
                UriBuilder.fromUri(portUri).path("vpns").build());
        response = resource.type(APPLICATION_VPN_JSON).get(
                ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));

        //Delete the vpn
        response = response = resource().uri(vpnUri).type(APPLICATION_VPN_JSON).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}
