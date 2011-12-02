package com.midokura.midonet.smoketest.topology;

import java.util.UUID;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mgmt.DtoRule;
import com.midokura.midonet.smoketest.mgmt.DtoRuleChain;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class Rule {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoRuleChain chain;
        DtoRule rule;

        public Builder(MidolmanMgmt mgmt, DtoRuleChain chain) {
            this.mgmt = mgmt;
            this.chain = chain;
            this.rule = new DtoRule();
            rule.setPosition(1);
        }

        public Builder setSimpleType(String type) {
            rule.setFlowAction(type);
            rule.setType(type);
            return this;
        }

        public Builder setDnat(IntIPv4 dst, int port) {
            rule.setType(DtoRule.DNAT);
            rule.setFlowAction(DtoRule.Accept);
            String[][][] target = new String[1][2][];
            target[0][0] = new String[] { dst.toString(), dst.toString() };
            String p = new Integer(port).toString();
            target[0][1] = new String[] { p, p };
            rule.setNatTargets(target);
            return this;
        }

        public Builder setSnat(IntIPv4 src, int port) {
            rule.setType(DtoRule.SNAT);
            rule.setFlowAction(DtoRule.Accept);
            String[][][] target = new String[1][2][];
            target[0][0] = new String[] { src.toString(), src.toString() };
            String p = new Integer(port).toString();
            target[0][1] = new String[] { p, p };
            rule.setNatTargets(target);
            return this;
        }

        public Builder setMatchNwDst(IntIPv4 addr, int length) {
            rule.setNwDstAddress(addr.toString());
            rule.setNwDstLength(length);
            return this;
        }

        public Builder setMatchNwSrc(IntIPv4 addr, int length) {
            rule.setNwSrcAddress(addr.toString());
            rule.setNwSrcLength(length);
            return this;
        }

        public Builder setMatchInPort(UUID vportId) {
            rule.setInPorts(new UUID[] { vportId });
            return this;
        }

        public Builder setMatchOutPort(UUID vportId) {
            rule.setOutPorts(new UUID[] { vportId });
            return this;
        }

        public Rule build() {
            return new Rule(mgmt, mgmt.addRule(chain, rule));
        }
    }

    MidolmanMgmt mgmt;
    DtoRule rule;

    Rule(MidolmanMgmt mgmt, DtoRule rule) {
        this.mgmt = mgmt;
        this.rule = rule;
    }
}
