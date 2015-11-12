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
package org.midonet.cluster.rest_api.filter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Suite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.cluster.rest_api.rest_api.TopologyBackdoor;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoPortGroup;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.dto.DtoRule.DtoNatTarget;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.cluster.rest_api.models.Rule;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.packets.ARP;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv6;
import org.midonet.packets.LLDP;
import org.midonet.packets.Unsigned;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_L4_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_NAT_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_UNDEFINED;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_RULE_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_RULE_JSON_V2;

@RunWith(Suite.class)
@Suite.SuiteClasses({TestRule.TestRuleCreateBadRequest.class,
                     TestRule.TestMacFields.class,
                     TestRule.TestFragmentPolicy.class,
                     TestRule.TestRuleCrudSuccess.class})
public class TestRule {

    private final static Logger log = LoggerFactory.getLogger(TestRule.class);

    public static abstract class TestRuleBase extends RestApiTestBase {

        protected DtoRuleChain chain1;

        protected TestRuleBase() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() throws Exception {
            super.setUp();
            chain1 = topology.getChain("chain1");
        }

        protected void extendTopology(Topology.Builder builder) {
            super.extendTopology(builder);

            DtoRuleChain chain = new DtoRuleChain();
            chain.setName("chain1-name");
            chain.setTenantId("tenant1-id");

            builder.create("chain1", chain);
        }

        protected DtoRule newAcceptRule() {
            DtoRule rule = new DtoRule();
            rule.setType(DtoRule.Accept);
            return rule;
        }
    }

    @RunWith(Parameterized.class)
    public static class TestRuleCreateBadRequest extends TestRuleBase {

        private final DtoRule rule;
        private final String property;

        public TestRuleCreateBadRequest(DtoRule rule, String property) {
            this.rule = rule;
            this.property = property;
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<>();

            // Null type
            DtoRule nullType = new DtoRule();
            nullType.setType(null);
            params.add(new Object[]{nullType, "type"});

            // Invalid type
            DtoRule invalidType = new DtoRule();
            invalidType.setType("badType");
            params.add(new Object[]{invalidType, "type"});

            // Invalid L2 transform bad flow action
            DtoRule l2TransformBadFlowAction = new DtoRule();
            l2TransformBadFlowAction.setType(DtoRule.L2Transform);
            l2TransformBadFlowAction.setFlowAction("badAction");
            params.add(new Object[] { l2TransformBadFlowAction, "flowAction" });

            // Invalid L2 transform invalid flow action
            DtoRule l2TransformInvalidFlowAction = new DtoRule();
            l2TransformInvalidFlowAction.setType(DtoRule.L2Transform);
            l2TransformInvalidFlowAction.setFlowAction(DtoRule.Drop);
            params.add(new Object[] { l2TransformInvalidFlowAction, "flowAction" });

            // Invalid L2 transform invalid push VLAN
            DtoRule l2TransformInvalidPushVlan = new DtoRule();
            l2TransformInvalidPushVlan.setType(DtoRule.L2Transform);
            l2TransformInvalidPushVlan.setFlowAction(DtoRule.Accept);
            l2TransformInvalidPushVlan.setPushVlan(0x10000);
            params.add(new Object[] { l2TransformInvalidPushVlan, "pushVlan" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(),
                    rule);
            List<Map<String, String>> violations = error.getViolations();
            if (violations.size() == 1) {
                assertEquals(violations.get(0).get("property"), property);
            }
        }
    }

    public static class TestMacFields extends TestRuleBase {

        @Test
        public void testCreateWithBadDlSrc() {
            DtoRule r = newAcceptRule();
            r.setDlSrc("01:23:45:67:89:0ab");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), r);
            assertErrorMatches(error, MessageProperty.MAC_ADDRESS_INVALID);
        }

        @Test
        public void testCreateWithBadDlDst() {
            DtoRule r = newAcceptRule();
            r.setDlDst("01:23:45:67:89:0ab");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), r);
            assertErrorMatches(error, MessageProperty.MAC_ADDRESS_INVALID);
        }

        @Test
        public void testCreateWithBadDlSrcMask() {
            DtoRule r = newAcceptRule();
            r.setDlSrcMask("fffff.0000.0000");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), r);
            assertErrorMatches(error, MessageProperty.MAC_MASK_INVALID);
        }

        @Test
        public void testCreateWithBadDlDstMask() {
            DtoRule r = newAcceptRule();
            r.setDlDstMask("fffff.0000.0000");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), r);
            assertErrorMatches(error, MessageProperty.MAC_MASK_INVALID);
        }

        @Test
        public void testCreateWithValidMacsAndMacMasks() {
            DtoRule r = newAcceptRule();
            r.setDlSrc("01:02:03:04:05:06");
            r.setDlSrcMask("ffff.ffff.0000");
            r.setDlDst("10:20:30:40:50:60");
            r.setDlDstMask("0000.ffff.ffff");
            DtoRule created = dtoResource.postAndVerifyCreated(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), r,
                    DtoRule.class);
            assertEquals(r.getDlSrc(), created.getDlSrc());
            assertEquals(r.getDlSrcMask(), created.getDlSrcMask());
            assertEquals(r.getDlDst(), created.getDlDst());
            assertEquals(r.getDlDstMask(), created.getDlDstMask());
        }
    }

    public static class TestFragmentPolicy extends TestRuleBase {

        @Test
        public void testDefaultPolicy() {
            DtoRule rule = newAcceptRule();
            rule = dtoResource.postAndVerifyCreated(chain1.getRules(),
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            assertEquals("any", rule.getFragmentPolicy());
        }

        @Test
        public void testUndefinedPolicy() {
            DtoRule rule = newAcceptRule();
            rule.setFragmentPolicy("UNDEFINED");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), rule);
            assertErrorMatches(error, FRAG_POLICY_UNDEFINED);
        }

        @Test
        public void testDefaultPolicyWithL4Field() {
            DtoRule rule = newAcceptRule();
            rule.setTpDst(new DtoRule.DtoRange<>(1234, 1234));
            rule = dtoResource.postAndVerifyCreated(chain1.getRules(),
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            assertEquals("header", rule.getFragmentPolicy());
        }

        @Test
        public void testPoliciesAllowedWithL4Field() {
            DtoRule rule = newAcceptRule();
            rule.setTpDst(new DtoRule.DtoRange<>(1234, 1234));
            assertPolicyRejected(rule, "any");
            assertPolicyRejected(rule, "nonheader");
            assertPolicyAccepted(rule, "header");
            assertPolicyAccepted(rule, "unfragmented");
        }

        @Test
        public void testDefaultPolicyWithForwardNatRuleWithNoL4Properties() {
            DtoRule rule = newForwardDnatRule();
            rule = dtoResource.postAndVerifyCreated(chain1.getRules(),
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            assertEquals("any", rule.getFragmentPolicy());
        }

        @Test
        public void testPoliciesAllowedWithForwardNatRuleWithNoL4Properties() {
            DtoRule rule = newForwardDnatRule();
            assertPolicyAccepted(rule, "any");
        }

        @Test
        public void testDefaultPolicyWithForwardNatRuleWithL4Property() {
            DtoRule rule = newForwardDnatRule();
            rule.setTpDst(new DtoRule.DtoRange<>(1234, 1234));
            rule = dtoResource.postAndVerifyCreated(chain1.getRules(),
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            assertEquals("unfragmented", rule.getFragmentPolicy());
        }

        @Test
        public void testPoliciesAllowedWithForwardNatRuleWithL4Property() {
            DtoRule rule = newForwardDnatRule();
            rule.setTpDst(new DtoRule.DtoRange<>(1234, 1234));
            assertPolicyRejected(rule, "any");
            assertPolicyRejected(rule, "header");
            assertPolicyRejected(rule, "nonheader");
            assertPolicyAccepted(rule, "unfragmented");
        }

        @Test
        public void testDefaultPolicyWithForwardNatRuleWithMultipleTargets() {
            DtoRule rule = newForwardDnatRule();
            rule.setNatTargets(new DtoNatTarget[]{
                    new DtoNatTarget("10.10.10.10", "10.10.10.11", 0, 0)});
            rule = dtoResource.postAndVerifyCreated(chain1.getRules(),
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            assertEquals("unfragmented", rule.getFragmentPolicy());
        }

        @Test
        public void testPoliciesAllowedWithForwardNatRuleWithMultipleTargets() {
            DtoRule rule = newForwardDnatRule();
            rule.setNatTargets(new DtoNatTarget[]{
                    new DtoNatTarget("10.10.10.10", "10.10.10.11", 0, 0)});
            assertPolicyRejected(rule, "any");
            assertPolicyRejected(rule, "header");
            assertPolicyRejected(rule, "nonheader");
            assertPolicyAccepted(rule, "unfragmented");
        }

        private void assertPolicyRejected(DtoRule r, String fp) {
            r.setFragmentPolicy(fp);
            DtoError e = dtoResource.postAndVerifyBadRequest(
                    chain1.getRules(), APPLICATION_RULE_JSON_V2(), r);
            boolean isForwardNat = r.getType().equals(DtoRule.DNAT) ||
                                   r.getType().equals(DtoRule.SNAT);
            assertErrorMatches(e, isForwardNat ?
                    FRAG_POLICY_INVALID_FOR_NAT_RULE :
                    FRAG_POLICY_INVALID_FOR_L4_RULE);
        }

        private void assertPolicyAccepted(DtoRule r, String fp) {
            r.setFragmentPolicy(fp);
            r = dtoResource.postAndVerifyCreated(chain1.getRules(),
                    APPLICATION_RULE_JSON_V2(), r, DtoRule.class);
            assertEquals(fp, r.getFragmentPolicy());
        }

        private DtoRule newForwardDnatRule() {
            DtoRule rule = new DtoRule();
            rule.setType(DtoRule.DNAT);
            rule.setFlowAction("accept");
            rule.setNatTargets(new DtoNatTarget[]{
                    new DtoNatTarget("10.10.10.10", "10.10.10.10", 0, 0)});
            return rule;
        }
    }

    @RunWith(Parameterized.class)
    public static class TestEtherType extends TestRuleBase {

        private short etherType;

        public TopologyBackdoor topologyBackdoor = FuncTest._injector
            .getInstance(TopologyBackdoor.class);

        public TestEtherType(short etherType) {
            this.etherType = etherType;
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][] {
                {ARP.ETHERTYPE}, {IPv4.ETHERTYPE}, {IPv6.ETHERTYPE},
                {LLDP.ETHERTYPE}});
        }

        @Test
        public void testNegativeEtherType() throws Exception {
            UUID chainId = topologyBackdoor.createChain();

            DtoApplication app = topology.getApplication();
            UUID ruleId = topologyBackdoor.createRule(chainId, etherType);

            URI ruleUri = UriBuilder.fromUri(
                app.getRuleTemplate().replace("{id}", ruleId.toString())).build();
            DtoRule outRule = dtoResource.getAndVerifyOk(
                ruleUri, APPLICATION_RULE_JSON_V2(), DtoRule.class);
            assertEquals(outRule.getDlType().intValue(),
                         Unsigned.unsign(etherType));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestRuleCrudSuccess extends TestRuleBase {

        private DtoRule rule;
        private String portGroupTag;

        public TestRuleCrudSuccess(DtoRule rule, String portGroupTag) {
            this.rule = rule;
            this.portGroupTag = portGroupTag;
        }

        @Before
        public void setUp() throws Exception {
            super.setUp();

            // Set the port group to the rule if it's instructed to do so.
            if (portGroupTag != null) {
                DtoPortGroup portGroup = topology.getPortGroup(portGroupTag);
                rule.setPortGroup(portGroup.getId());
            }
        }

        @Override
        protected void extendTopology(Topology.Builder builder) {
            super.extendTopology(builder);

            // Create a port group
            DtoPortGroup pg1 = new DtoPortGroup();
            pg1.setName("portgroup1-name");
            pg1.setTenantId("tenant1-id");

            builder.create("portgroup1", pg1);
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {

            DtoRule dnatRule = new DtoRule();
            log.debug("type rule: {}", Rule.class);
            DtoNatTarget[] natTargets = new DtoNatTarget[] {
                    new DtoNatTarget("192.168.100.1", "192.168.100.6", 80, 8080),
                    new DtoNatTarget("192.168.100.7", "192.168.100.10", 8081,
                            8089) };
            Map<String, String> properties = new HashMap<>();
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
            dnatRule.setTpSrc(new DtoRule.DtoRange<>(1024, 3000));
            dnatRule.setTpDst(new DtoRule.DtoRange<>(1024, 3000));
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
            revDnatRule.setTpSrc(new DtoRule.DtoRange<>(1024, 3000));
            revDnatRule.setTpDst(new DtoRule.DtoRange<>(1024, 3000));
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
            snatRule.setTpSrc(new DtoRule.DtoRange<>(1024, 3000));
            snatRule.setTpDst(new DtoRule.DtoRange<>(1024, 3000));
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
            revSnatRule.setTpSrc(new DtoRule.DtoRange<>(1024, 3000));
            revSnatRule.setTpDst(new DtoRule.DtoRange<>(1024, 3000));
            revSnatRule.setInvTpDst(true);
            revSnatRule.setType("rev_snat");
            revSnatRule.setFlowAction("accept");
            revSnatRule.setNatTargets(natTargets);
            revSnatRule.setPosition(1);
            revSnatRule.setProperties(properties);

            DtoRule filteringRule = new DtoRule();
            filteringRule.setMatchForwardFlow(true);
            filteringRule.setDlDst("aa:bb:cc:dd:ee:ff");
            filteringRule.setDlSrc("11:22:33:44:55:66");
            filteringRule.setDlType(Unsigned.unsign(ARP.ETHERTYPE));
            filteringRule.setType(DtoRule.Drop);
            filteringRule.setPosition(1);
            filteringRule.setProperties(properties);

            DtoRule l2TransformRuleAccept = new DtoRule();
            l2TransformRuleAccept.setType(DtoRule.L2Transform);
            l2TransformRuleAccept.setFlowAction(DtoRule.Accept);
            l2TransformRuleAccept.setPopVlan(true);
            l2TransformRuleAccept.setPushVlan(0x7000);
            l2TransformRuleAccept.setTargetPortId(UUID.randomUUID());
            l2TransformRuleAccept.setIngress(true);
            l2TransformRuleAccept.setFailOpen(true);
            l2TransformRuleAccept.setPosition(1);
            l2TransformRuleAccept.setProperties(properties);

            DtoRule l2TransformRuleRedirect = new DtoRule();
            l2TransformRuleRedirect.setType(DtoRule.L2Transform);
            l2TransformRuleRedirect.setFlowAction(DtoRule.Redirect);
            l2TransformRuleRedirect.setPopVlan(true);
            l2TransformRuleRedirect.setPushVlan(0x7000);
            l2TransformRuleRedirect.setTargetPortId(UUID.randomUUID());
            l2TransformRuleRedirect.setIngress(true);
            l2TransformRuleRedirect.setFailOpen(true);
            l2TransformRuleRedirect.setPosition(1);
            l2TransformRuleRedirect.setProperties(properties);

            return Arrays.asList(new Object[][] { { dnatRule, null },
                    { revDnatRule, null }, { snatRule, null },
                    { revSnatRule, null }, { filteringRule, "portgroup1" },
                    { l2TransformRuleAccept, null},
                    { l2TransformRuleRedirect, null}});
        }

        @Test
        public void testCreateGetListDelete() {

            rule.setChainId(chain1.getId());

            // Verify that there is nothing
            URI rulesUri = chain1.getRules();
            assertNotNull(rulesUri);
            DtoRule[] rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON_V2(), DtoRule[].class);
            assertEquals(0, rules.length);

            // Add a rule
            DtoRule outRule = dtoResource.postAndVerifyCreated(rulesUri,
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            // TODO: Implement 'equals' for DtoRule
            assertEquals(rule.getType(), outRule.getType());
            assertEquals(1, outRule.getPosition());
            URI rule1Uri = outRule.getUri();

            // List the rule
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON_V2(), DtoRule[].class);
            assertEquals(1, rules.length);
            assertEquals(outRule.getId(), rules[0].getId());

            // Add this rule to position 1
            outRule = dtoResource.postAndVerifyCreated(rulesUri,
                    APPLICATION_RULE_JSON_V2(), rule, DtoRule.class);
            assertEquals(rule.getType(), outRule.getType());
            assertEquals(1, outRule.getPosition());
            URI rule2Uri = outRule.getUri();

            // Get the original rule
            outRule = dtoResource.getAndVerifyOk(rule1Uri,
                    APPLICATION_RULE_JSON_V2(), DtoRule.class);
            assertEquals(rule.getType(), outRule.getType());
            assertEquals(2, outRule.getPosition());

            // List both rules
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON_V2(), DtoRule[].class);
            assertEquals(2, rules.length);
            assertEquals(1, rules[0].getPosition());
            assertEquals(2, rules[1].getPosition());

            // Delete one of the rules
            dtoResource.deleteAndVerifyNoContent(rule1Uri,
                                                 APPLICATION_RULE_JSON_V2());

            // Verify that the rule is gone
            dtoResource.getAndVerifyNotFound(rule1Uri,
                    APPLICATION_RULE_JSON_V2());

            // List and make sure there is only one
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON_V2(), DtoRule[].class);
            assertEquals(1, rules.length);

            // Delete the second rule
            dtoResource.deleteAndVerifyNoContent(rule2Uri,
                    APPLICATION_RULE_JSON_V2());

            // Verify that the rule is gone
            dtoResource.getAndVerifyNotFound(rule2Uri,
                    APPLICATION_RULE_JSON_V2());

            // List should return nothing now.
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON_V2(), DtoRule[].class);
            assertEquals(0, rules.length);
        }
    }
}
