/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.JumpRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.RuleResult.Action;

@RunWith(Enclosed.class)
public class TestRule {

    @RunWith(Parameterized.class)
    public static class TestObjectCreation {

        private final UUID inputId;
        private final com.midokura.midolman.rules.Rule inputRule;
        private final Rule expected;

        public TestObjectCreation(UUID inputId,
                com.midokura.midolman.rules.Rule inputRule, Rule expected) {
            this.inputId = inputId;
            this.inputRule = inputRule;
            this.expected = expected;
        }

        @Parameters
        public static Collection<Object[]> input() {

            UUID id = UUID.randomUUID();
            UUID chainId = UUID.randomUUID();

            // TODO: Add conditions for these tests
            Condition cond = new Condition();

            LiteralRule acceptRule = new LiteralRule(cond, Action.ACCEPT);
            acceptRule.chainId = chainId;
            acceptRule.position = 1;

            Rule acceptRuleExpected = new Rule();
            acceptRuleExpected.setId(id);
            acceptRuleExpected.setType(Rule.Accept);
            acceptRuleExpected.setChainId(chainId);
            acceptRuleExpected.setPosition(1);

            JumpRule jumpRule = new JumpRule(cond, UUID.randomUUID(), "foo");
            jumpRule.chainId = chainId;
            jumpRule.position = 2;

            Rule jumpDropRuleExpected = new Rule();
            jumpDropRuleExpected.setId(id);
            jumpDropRuleExpected.setType(Rule.Jump);
            jumpDropRuleExpected.setChainId(chainId);
            jumpDropRuleExpected.setPosition(2);
            jumpDropRuleExpected.setJumpChainName("foo");

            Object[][] data = new Object[][] {
                    { id, acceptRule, acceptRuleExpected },
                    { id, jumpRule, jumpDropRuleExpected } };

            return Arrays.asList(data);
        }

        @Test
        public void testAllFieldsMatch() {

            // Execute
            Rule actual = new Rule(inputId, inputRule);

            // Verify
            Assert.assertEquals(expected.getId(), inputId);
            Assert.assertEquals(expected.getType(), actual.getType());
            Assert.assertEquals(expected.getChainId(), actual.getChainId());
            Assert.assertEquals(expected.getPosition(), actual.getPosition());
            Assert.assertEquals(expected.getJumpChainName(),
                    actual.getJumpChainName());
        }
    }

}
