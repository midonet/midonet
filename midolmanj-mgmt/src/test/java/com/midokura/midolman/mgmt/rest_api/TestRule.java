/*
 * @(#)testVif        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_CHAIN_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;

@RunWith(Parameterized.class)
public class TestRule extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestRule.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;

    private URI ruleChainUri;

    private DtoRouter router = new DtoRouter();
    private DtoRule rule;

    public TestRule(DtoRule rule) {
        super(FuncTest.appDesc);
        this.rule = rule;
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
        URI testTenantUri = response.getLocation();
        assertTrue(
                testTenantUri.toString().endsWith("tenants/" + testTenantName));

        // Create a router.
        router.setName(testRouterName);
        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
            ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        URI testRouterUri = response.getLocation();

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

        response = resource().uri(routerPortUri)
            .type(APPLICATION_PORT_JSON)
            .post(ClientResponse.class, port);
        assertEquals(201, response.getStatus());
        log.debug("location: {}", response.getLocation());

        UUID testRouterPortId = FuncTest.getUuidFromLocation(response.getLocation());

        // Create a chain
        DtoRuleChain ruleChain = new DtoRuleChain();
        ruleChain.setName("foo_chain");

        URI routerChainUri = URI.create(testTenantUri.toString() + "/chains");
        response = resource().uri(routerChainUri)
            .type(APPLICATION_CHAIN_JSON)
            .post(ClientResponse.class, ruleChain);
        ruleChainUri = response.getLocation();
        log.debug("status {}", response.getStatus());
        log.debug("location {}", response.getLocation());
        assertEquals(201, response.getStatus());
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {

        DtoRule dnatRule = new DtoRule();
        log.debug("type rule: {}", Rule.class);
        UUID[] inPorts = new UUID[]{UUID.randomUUID()};
        String[][][] natTargets = new String[2][2][];
        natTargets[0][0] = new String[]{"192.168.100.1", "192.168.100.6"};
        natTargets[0][1] = new String[]{"80", "8080"};
        natTargets[1][0] = new String[]{"192.168.100.7", "192.168.100.10"};
        natTargets[1][1] = new String[]{"8081", "8089"};

        dnatRule.setCondInvert(true);
        dnatRule.setInPorts(inPorts);
        dnatRule.setInvInPorts(true);
        dnatRule.setInvOutPorts(true);
        dnatRule.setNwTos(20);
        dnatRule.setInvNwTos(true);
        dnatRule.setNwProto(6);
        dnatRule.setInvNwProto(true);
        dnatRule.setNwSrcAddress("10.0.0.2");
        dnatRule.setNwSrcLength(24);
        dnatRule.setInvNwSrc(true);
        dnatRule.setNwDstAddress("192.168.100.10");
        dnatRule.setNwDstLength(32);
        dnatRule.setInvNwDst(true);
        dnatRule.setTpSrcStart((short) 1024);
        dnatRule.setTpSrcEnd((short) 3000);
        dnatRule.setTpDstStart((short) 1024);
        dnatRule.setTpDstEnd((short) 3000);
        dnatRule.setInvTpDst(true);
        dnatRule.setType("dnat");
        dnatRule.setFlowAction("accept");
        dnatRule.setNatTargets(natTargets);
        dnatRule.setPosition(1);

        DtoRule revDnatRule = new DtoRule();
        revDnatRule.setCondInvert(true);
        revDnatRule.setInPorts(inPorts);
        revDnatRule.setInvInPorts(true);
        revDnatRule.setInvOutPorts(true);
        revDnatRule.setNwTos(20);
        revDnatRule.setInvNwTos(true);
        revDnatRule.setNwProto(6);
        revDnatRule.setInvNwProto(true);
        revDnatRule.setNwSrcAddress("10.0.0.2");
        revDnatRule.setNwSrcLength(24);
        revDnatRule.setInvNwSrc(true);
        revDnatRule.setNwDstAddress("192.168.100.10");
        revDnatRule.setNwDstLength(32);
        revDnatRule.setInvNwDst(true);
        revDnatRule.setTpSrcStart((short) 1024);
        revDnatRule.setTpSrcEnd((short) 3000);
        revDnatRule.setTpDstStart((short) 1024);
        revDnatRule.setTpDstEnd((short) 3000);
        revDnatRule.setInvTpDst(true);
        revDnatRule.setType("rev_dnat");
        revDnatRule.setFlowAction("accept");
        revDnatRule.setNatTargets(natTargets);
        revDnatRule.setPosition(1);

        DtoRule snatRule = new DtoRule();
        snatRule.setCondInvert(true);
        snatRule.setInPorts(inPorts);
        snatRule.setInvInPorts(true);
        snatRule.setInvOutPorts(true);
        snatRule.setNwTos(20);
        snatRule.setInvNwTos(true);
        snatRule.setNwProto(6);
        snatRule.setInvNwProto(true);
        snatRule.setNwSrcAddress("10.0.0.2");
        snatRule.setNwSrcLength(24);
        snatRule.setInvNwSrc(true);
        snatRule.setNwDstAddress("192.168.100.10");
        snatRule.setNwDstLength(32);
        snatRule.setInvNwDst(true);
        snatRule.setTpSrcStart((short) 1024);
        snatRule.setTpSrcEnd((short) 3000);
        snatRule.setTpDstStart((short) 1024);
        snatRule.setTpDstEnd((short) 3000);
        snatRule.setInvTpDst(true);
        snatRule.setType("snat");
        snatRule.setFlowAction("accept");
        snatRule.setNatTargets(natTargets);
        snatRule.setPosition(1);

        DtoRule revSnatRule = new DtoRule();
        revSnatRule.setCondInvert(true);
        revSnatRule.setInPorts(inPorts);
        revSnatRule.setInvInPorts(true);
        revSnatRule.setInvOutPorts(true);
        revSnatRule.setNwTos(20);
        revSnatRule.setInvNwTos(true);
        revSnatRule.setNwProto(6);
        revSnatRule.setInvNwProto(true);
        revSnatRule.setNwSrcAddress("10.0.0.2");
        revSnatRule.setNwSrcLength(24);
        revSnatRule.setInvNwSrc(true);
        revSnatRule.setNwDstAddress("192.168.100.10");
        revSnatRule.setNwDstLength(32);
        revSnatRule.setInvNwDst(true);
        revSnatRule.setTpSrcStart((short) 1024);
        revSnatRule.setTpSrcEnd((short) 3000);
        revSnatRule.setTpDstStart((short) 1024);
        revSnatRule.setTpDstEnd((short) 3000);
        revSnatRule.setInvTpDst(true);
        revSnatRule.setType("rev_snat");
        revSnatRule.setFlowAction("accept");
        revSnatRule.setNatTargets(natTargets);
        revSnatRule.setPosition(1);

        return Arrays.asList(
            new Object[][]{{dnatRule}, {revDnatRule}, {snatRule}, {revSnatRule}});
    }

    @Test
    public void testCreateGetListDelete() {

        URI rulesUri = URI.create(ruleChainUri.toString() + "/rules");
        // Create up
        response = resource().uri(rulesUri)
            .type(APPLICATION_RULE_JSON)
            .post(ClientResponse.class, rule);
        log.debug("status {}", response.getStatus());
        assertEquals(201, response.getStatus());
        URI ruleUri = response.getLocation();

        // Get the rule
        response = resource().uri(ruleUri)
            .accept(APPLICATION_RULE_JSON)
            .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoRule ruleReturned = response.getEntity(DtoRule.class);
        //NOTE(tomoe) just check type field since other fields
        //            are straightforward data transformation
        assertEquals(rule.getType(), ruleReturned.getType());
        assertEquals(1, ruleReturned.getPosition());

        // List rules
        response = resource().uri(
            URI.create(ruleChainUri.toString() + "/rules"))
            .accept(APPLICATION_RULE_COLLECTION_JSON)
            .get(ClientResponse.class);
        log.debug("{}", response.getEntity(String.class));
        assertEquals(200, response.getStatus());

        // Now insert a copy of the rule in position 1.
        response = resource().uri(rulesUri)
                .type(APPLICATION_RULE_JSON)
                .post(ClientResponse.class, rule);
        assertEquals(201, response.getStatus());
        URI newRuleUri = response.getLocation();

        // Verify that the original rule has been pushed to position 2.
        response = resource().uri(ruleUri)
                .accept(APPLICATION_RULE_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        ruleReturned = response.getEntity(DtoRule.class);
        assertEquals(2, ruleReturned.getPosition());

        // Now delete the rule at position 1.
        response = resource().uri(newRuleUri)
                .type(APPLICATION_RULE_JSON)
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());

        // Verify that the original rule has been pushed back to position 1.
        response = resource().uri(ruleUri)
                .accept(APPLICATION_RULE_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        ruleReturned = response.getEntity(DtoRule.class);
        assertEquals(1, ruleReturned.getPosition());

        //Delete the chain
        response = resource().uri(ruleUri).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}
