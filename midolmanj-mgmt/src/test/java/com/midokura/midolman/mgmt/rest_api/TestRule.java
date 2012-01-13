/*
 * @(#)testVif        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.client.*;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
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

public class TestRule extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestRule.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;

    private UUID testRouterPortId;
    private URI ruleChainUri;

    DtoRouter router = new DtoRouter();

    public TestRule() {
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

        // Create a materialized router port.
        URI routerPortUri = URI.create(testRouterUri.toString() + "/ports");
        log.debug("routerPortUri: {} ", routerPortUri);
        DtoMaterializedRouterPort port = new DtoMaterializedRouterPort();
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        port.setLocalNetworkAddress("10.0.0.2");
        port.setLocalNetworkLength(32);
        port.setVifId(UUID.fromString("372b0040-12ae-11e1-be50-0800200c9a66"));

        response = resource().uri(routerPortUri).type(APPLICATION_PORT_JSON).post(ClientResponse.class, port);
        assertEquals(201, response.getStatus());
        log.debug("location: {}", response.getLocation());

        testRouterPortId = FuncTest.getUuidFromLocation(response.getLocation());

        // Create a chain
        DtoRuleChain ruleChain = new DtoRuleChain();
        ruleChain.setName("foo_chain");
        ruleChain.setTable(ChainTable.NAT);

        URI routerChainUri = URI.create(testRouterUri.toString() + "/chains");
        response = resource().uri(routerChainUri).type(APPLICATION_CHAIN_JSON).post(ClientResponse.class, ruleChain);
        ruleChainUri = response.getLocation();
        log.debug("status {}", response.getStatus());
        log.debug("location {}", response.getLocation());
        assertEquals(201, response.getStatus());
    }

    @Test
    public void testCreateGetListDelete() {
        DtoRule rule = new DtoRule();
        log.debug("type rule: {}", Rule.class);
        UUID[] inPorts = new UUID[]{UUID.randomUUID()};
        String[][][] natTargets = new String[2][2][];
        natTargets[0][0] = new String[]{"192.168.100.1", "192.168.100.6"};
        natTargets[0][1] = new String[]{"80", "8080"};
        natTargets[1][0] = new String[]{"192.168.100.7", "192.168.100.10"};
        natTargets[1][1] = new String[]{"8081", "8089"};

        URI rulesUri = URI.create(ruleChainUri.toString() + "/rules");
        rule.setCondInvert(true);
        rule.setInPorts(inPorts);
        rule.setInvInPorts(true);
        rule.setInvOutPorts(true);
        rule.setNwTos(20);
        rule.setInvNwTos(true);
        rule.setNwProto(6);
        rule.setInvNwProto(true);
        rule.setNwSrcAddress("10.0.0.2");
        rule.setNwSrcLength(24);
        rule.setInvNwSrc(true);
        rule.setNwDstAddress("192.168.100.10");
        rule.setNwDstLength(32);
        rule.setInvNwDst(true);
        rule.setTpSrcStart((short) 1024);
        rule.setTpSrcEnd((short) 3000);
        rule.setTpDstStart((short) 1024);
        rule.setTpDstEnd((short) 3000);
        rule.setInvTpDst(true);
        rule.setType("dnat");
        rule.setFlowAction("accept");
        rule.setNatTargets(natTargets);
        rule.setPosition(1);
        log.debug(" rulesUri: {}", rulesUri);

        // Create up
        response = resource().uri(rulesUri).type(APPLICATION_RULE_JSON).post(ClientResponse.class, rule);
        log.debug("status {}", response.getStatus());
        assertEquals(201, response.getStatus());
        URI ruleUri = response.getLocation();

        // Get the rule
        response = resource().uri(ruleUri).accept(APPLICATION_RULE_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));

        // List rules
        response = resource().uri(URI.create(ruleChainUri.toString() + "/rules")).accept(APPLICATION_RULE_COLLECTION_JSON).get(ClientResponse.class);
        log.debug("{}", response.getEntity(String.class));
        assertEquals(200, response.getStatus());

        //Delete the chain
        response = resource().uri(ruleUri).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}
