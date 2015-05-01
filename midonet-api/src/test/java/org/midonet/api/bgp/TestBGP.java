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
package org.midonet.api.bgp;

import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.services.rest_api.VendorMediaType;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.dto.DtoBgp;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;

import static org.junit.Assert.assertEquals;

public class TestBGP extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestBGP.class);
    private final String testTenantId = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;

    private UUID testRouterPortId;

    DtoRouter router = new DtoRouter();

    public TestBGP() {
        super(FuncTest.appDesc);
    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() {

        // Create a router.
        router.setName(testRouterName);
        router.setTenantId(testTenantId);
        resource = resource().path("/routers");
        response = resource.type(VendorMediaType.APPLICATION_ROUTER_JSON_V2).post(
                ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        testRouterUri = response.getLocation();

        // Create a Exterior router port.
        URI routerPortUri = URI.create(testRouterUri.toString() + "/ports");
        log.debug("routerPortUri: {} ", routerPortUri);
        DtoRouterPort port = new DtoRouterPort();
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        port.setVifId(UUID.fromString("372b0040-12ae-11e1-be50-0800200c9a66"));

        response = resource().uri(routerPortUri).type(VendorMediaType.APPLICATION_PORT_V2_JSON)
                .post(ClientResponse.class, port);
        assertEquals(201, response.getStatus());
        log.debug("location: {}", response.getLocation());

        testRouterPortId = FuncTest.getUuidFromLocation(response.getLocation());
    }

    @Test
    public void testCreateGetListDelete() {
        DtoBgp bgp = new DtoBgp();
        bgp.setLocalAS(55394);
        bgp.setPeerAS(65104);
        bgp.setPeerAddr("180.214.47.65");

        //Create bgp
        response = resource().path("/ports/" + testRouterPortId + "/bgps")
                .type(VendorMediaType.APPLICATION_BGP_JSON).post(ClientResponse.class, bgp);
        assertEquals(201, response.getStatus());
        URI bgpUri = response.getLocation();

        //Get the bgp
        response = resource().uri(bgpUri).accept(VendorMediaType.APPLICATION_BGP_JSON)
                .get(ClientResponse.class);
        bgp = response.getEntity(DtoBgp.class);
        assertEquals(200, response.getStatus());
        assertEquals(55394, bgp.getLocalAS());
        assertEquals(65104, bgp.getPeerAS());
        assertEquals("180.214.47.65", bgp.getPeerAddr());
        log.debug("adRoute {}", bgp.getAdRoutes());

        //List bgps

        response = resource().path("/ports/" + testRouterPortId + "/bgps")
                .accept(VendorMediaType.APPLICATION_BGP_COLLECTION_JSON).get(
                        ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("BODY: {}", response.getEntity(String.class));

        //Delete the chain
        response = resource().uri(bgpUri).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}
