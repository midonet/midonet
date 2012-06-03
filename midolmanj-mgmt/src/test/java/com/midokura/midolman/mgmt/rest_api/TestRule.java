/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_RULE_JSON;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule.DtoNatTarget;
import com.midokura.midolman.packets.ARP;

@RunWith(Parameterized.class)
public class TestRule extends RuleJerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestRule.class);

    private DtoRule rule;

    public TestRule(DtoRule rule) {
        super();
        this.rule = rule;
    }

    @Before
    public void setUp() {
        super.setUp();
        this.rule.setChainId(chain1.getId());
        this.rule.setInPorts(new UUID[] { router1MatPort1.getId(),
                router1LogPort1.getId() });
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {

        DtoRule dnatRule = new DtoRule();
        log.debug("type rule: {}", Rule.class);
        DtoNatTarget[] natTargets = new DtoNatTarget[] {
                new DtoNatTarget("192.168.100.1", "192.168.100.6", 80, 8080),
                new DtoNatTarget("192.168.100.7", "192.168.100.10", 8081, 8089) };
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

        return Arrays.asList(new Object[][] { { dnatRule }, { revDnatRule },
                { snatRule }, { revSnatRule }, { filteringRule } });
    }

    private void verifyPropertiesExist(DtoRule rule) {
        assertNotNull(rule.getProperties());
        assertEquals(2, rule.getProperties().size());
        assertEquals("bar", rule.getProperties().get("foo"));
        assertEquals("boo", rule.getProperties().get("baz"));
    }

    @Test
    public void testCreateGetListDelete() {

        // Verify that there is nothing
        assertNotNull(chain1);
        URI rulesUri = chain1.getRules();
        assertNotNull(rulesUri);
        DtoRule[] rules = dtoResource.get(rulesUri,
                APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
        assertNotNull(rules);
        assertEquals(0, rules.length);

        // Add a rule
        URI rule1Uri = dtoResource.post(rulesUri, APPLICATION_RULE_JSON, rule);
        assertNotNull(rule1Uri);

        // Get the rule
        DtoRule outRule = dtoResource.get(rule1Uri, APPLICATION_RULE_JSON,
                DtoRule.class);
        assertNotNull(outRule);
        // TODO: Implement 'equals' for DtoRule
        assertEquals(rule.getType(), outRule.getType());
        assertEquals(1, outRule.getPosition());
        verifyPropertiesExist(outRule);

        // List the rule
        rules = dtoResource.get(rulesUri, APPLICATION_RULE_COLLECTION_JSON,
                DtoRule[].class);
        assertNotNull(rules);
        assertEquals(1, rules.length);
        assertEquals(outRule.getId(), rules[0].getId());

        // Add this rule to position 1
        URI rule2Uri = dtoResource.post(rulesUri, APPLICATION_RULE_JSON, rule);
        assertNotNull(rule2Uri);

        // Get the rule
        outRule = dtoResource.get(rule2Uri, APPLICATION_RULE_JSON,
                DtoRule.class);
        assertNotNull(outRule);
        assertEquals(rule.getType(), outRule.getType());
        assertEquals(1, outRule.getPosition());
        verifyPropertiesExist(outRule);

        // Get the original rule
        outRule = dtoResource.get(rule1Uri, APPLICATION_RULE_JSON,
                DtoRule.class);
        assertNotNull(outRule);
        assertEquals(rule.getType(), outRule.getType());
        assertEquals(2, outRule.getPosition());
        verifyPropertiesExist(outRule);

        // List both rules
        rules = dtoResource.get(rulesUri, APPLICATION_RULE_COLLECTION_JSON,
                DtoRule[].class);
        assertNotNull(rules);
        assertEquals(2, rules.length);

        // Delete one of the rules
        dtoResource.delete(rule1Uri, APPLICATION_RULE_JSON);

        // Verify that the rule is gone
        outRule = dtoResource.get(rule1Uri, APPLICATION_RULE_JSON,
                DtoRule.class, NOT_FOUND.getStatusCode());
        assertNull(outRule);

        // List and make sure there is only one
        rules = dtoResource.get(rulesUri, APPLICATION_RULE_COLLECTION_JSON,
                DtoRule[].class);
        assertNotNull(rules);
        assertEquals(1, rules.length);

        // Delete the second rule
        dtoResource.delete(rule2Uri, APPLICATION_RULE_JSON);

        // Verify that the rule is gone
        outRule = dtoResource.get(rule2Uri, APPLICATION_RULE_JSON,
                DtoRule.class, NOT_FOUND.getStatusCode());
        assertNull(outRule);

        // List should return nothing now.
        rules = dtoResource.get(rulesUri, APPLICATION_RULE_COLLECTION_JSON,
                DtoRule[].class);
        assertNotNull(rules);
        assertEquals(0, rules.length);
    }
}
