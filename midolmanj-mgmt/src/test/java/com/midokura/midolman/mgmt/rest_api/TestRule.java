/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.client.DtoError;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule.DtoNatTarget;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midolman.packets.ARP;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

@RunWith(Enclosed.class)
public class TestRule {

    private final static Logger log = LoggerFactory.getLogger(TestRule.class);

    @RunWith(Parameterized.class)
    public static class TestRuleCreateBadRequest extends JerseyTest {

        private final DtoRule rule;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRuleCreateBadRequest(DtoRule rule, String property) {
            super(FuncTest.appDesc);
            this.rule = rule;
            this.property = property;
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a tenant
            DtoTenant t = new DtoTenant();
            t.setId("tenant1-id");

            // Create a chain
            DtoRuleChain c1 = new DtoRuleChain();
            c1.setName("chain1-name");

            topology = new Topology.Builder(dtoResource).create("tenant1", t)
                    .create("tenant1", "chain1", c1).build();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null type
            DtoRule nullType = new DtoRule();
            nullType.setType(null);
            params.add(new Object[] { nullType, "type" });

            // Invalid type
            DtoRule invalidType = new DtoRule();
            invalidType.setType("badType");
            params.add(new Object[] { invalidType, "type" });

            return params;
        }

        @Test
        public void testBadInputCreate() {

            DtoRuleChain chain1 = topology.getChain("chain1");

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON, rule);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestRuleCrudSuccess extends JerseyTest {

        private DtoRule rule;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRuleCrudSuccess(DtoRule rule) {
            super(FuncTest.appDesc);
            this.rule = rule;
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a tenant
            DtoTenant t = new DtoTenant();
            t.setId("tenant1-id");

            // Create a chain
            DtoRuleChain c1 = new DtoRuleChain();
            c1.setName("chain1-name");

            topology = new Topology.Builder(dtoResource).create("tenant1", t)
                    .create("tenant1", "chain1", c1).build();
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {

            DtoRule dnatRule = new DtoRule();
            log.debug("type rule: {}", Rule.class);
            DtoNatTarget[] natTargets = new DtoNatTarget[] {
                    new DtoNatTarget("192.168.100.1", "192.168.100.6", 80, 8080),
                    new DtoNatTarget("192.168.100.7", "192.168.100.10", 8081,
                            8089) };
            Map<String, String> properties = new HashMap<String, String>();
            properties.put("foo", "bar");
            properties.put("baz", "boo");

            dnatRule.setCondInvert(true);
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
            dnatRule.setProperties(properties);

            DtoRule revDnatRule = new DtoRule();
            revDnatRule.setCondInvert(true);
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
            revDnatRule.setProperties(properties);

            DtoRule snatRule = new DtoRule();
            snatRule.setCondInvert(true);
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
            snatRule.setProperties(properties);

            DtoRule revSnatRule = new DtoRule();
            revSnatRule.setCondInvert(true);
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
            revSnatRule.setProperties(properties);

            DtoRule filteringRule = new DtoRule();
            filteringRule.setMatchForwardFlow(true);
            filteringRule.setPortGroup(UUID.randomUUID());
            filteringRule.setDlDst("aa:bb:cc:dd:ee:ff");
            filteringRule.setDlSrc("11:22:33:44:55:66");
            filteringRule.setDlType(ARP.ETHERTYPE);
            filteringRule.setType(DtoRule.Drop);
            filteringRule.setPosition(1);
            filteringRule.setProperties(properties);

            return Arrays.asList(new Object[][] { { dnatRule },
                    { revDnatRule }, { snatRule }, { revSnatRule },
                    { filteringRule } });
        }

        private void verifyPropertiesExist(DtoRule rule) {
            assertNotNull(rule.getProperties());
            assertEquals(2, rule.getProperties().size());
            assertEquals("bar", rule.getProperties().get("foo"));
            assertEquals("boo", rule.getProperties().get("baz"));
        }

        @Test
        public void testCreateGetListDelete() {

            DtoRuleChain chain1 = topology.getChain("chain1");
            rule.setChainId(chain1.getId());

            // Verify that there is nothing
            URI rulesUri = chain1.getRules();
            assertNotNull(rulesUri);
            DtoRule[] rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(0, rules.length);

            // Add a rule
            DtoRule outRule = dtoResource.postAndVerifyCreated(rulesUri,
                    APPLICATION_RULE_JSON, rule, DtoRule.class);
            // TODO: Implement 'equals' for DtoRule
            assertEquals(rule.getType(), outRule.getType());
            assertEquals(1, outRule.getPosition());
            verifyPropertiesExist(outRule);
            URI rule1Uri = outRule.getUri();

            // List the rule
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(1, rules.length);
            assertEquals(outRule.getId(), rules[0].getId());

            // Add this rule to position 1
            outRule = dtoResource.postAndVerifyCreated(rulesUri,
                    APPLICATION_RULE_JSON, rule, DtoRule.class);
            assertEquals(rule.getType(), outRule.getType());
            assertEquals(1, outRule.getPosition());
            verifyPropertiesExist(outRule);
            URI rule2Uri = outRule.getUri();

            // Get the original rule
            outRule = dtoResource.getAndVerifyOk(rule1Uri,
                    APPLICATION_RULE_JSON, DtoRule.class);
            assertEquals(rule.getType(), outRule.getType());
            assertEquals(2, outRule.getPosition());
            verifyPropertiesExist(outRule);

            // List both rules
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(2, rules.length);

            // Delete one of the rules
            dtoResource.deleteAndVerifyNoContent(rule1Uri,
                    APPLICATION_RULE_JSON);

            // Verify that the rule is gone
            dtoResource.getAndVerifyNotFound(rule1Uri, APPLICATION_RULE_JSON);

            // List and make sure there is only one
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(1, rules.length);

            // Delete the second rule
            dtoResource.deleteAndVerifyNoContent(rule2Uri,
                    APPLICATION_RULE_JSON);

            // Verify that the rule is gone
            dtoResource.getAndVerifyNotFound(rule2Uri, APPLICATION_RULE_JSON);

            // List should return nothing now.
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(0, rules.length);
        }
    }
}
