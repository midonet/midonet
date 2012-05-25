package com.midokura.midonet.functional_test.topology;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

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

        public Builder setPosition(int position) {
            rule.setPosition(position);
            return this;
        }

        public Builder setSimpleType(String type) {
            rule.setType(type);
            return this;
        }

        public Builder setDnat(IntIPv4 dst, int port) {
            rule.setType(DtoRule.DNAT);
            rule.setFlowAction(DtoRule.Accept);
            rule.setNatTargets(
                new DtoRule.NatTarget[]{
                    new DtoRule.NatTarget(dst.toString(), dst.toString(),
                                          port, port)
                });
            return this;
        }

        public Builder setJump(String targetChainName) {
            rule.setType(DtoRule.Jump);
            rule.setJumpChainName(targetChainName);
            return this;
        }

        public Builder setSnat(IntIPv4 src, int port) {
            rule.setType(DtoRule.SNAT);
            rule.setFlowAction(DtoRule.Accept);
            rule.setNatTargets(
                new DtoRule.NatTarget[]{
                    new DtoRule.NatTarget(src.toString(), src.toString(),
                                          port, port)
                });
            return this;
        }

        public Builder matchForwardFlow() {
            rule.setMatchForwardFlow(true);
            return this;
        }

        public Builder matchReturnFlow() {
            rule.setMatchReturnFlow(true);
            return this;
        }

        public Builder matchDlType(short dlType) {
            rule.setDlType(dlType);
            return this;
        }

        public Builder matchDlDst(MAC addr) {
            rule.setDlDst(addr.toString());
            return this;
        }

        public Builder matchDlSrc(MAC addr) {
            rule.setDlSrc(addr.toString());
            return this;
        }

        public Builder matchNwDst(IntIPv4 addr, int length) {
            rule.setNwDstAddress(addr.toString());
            rule.setNwDstLength(length);
            return this;
        }

        public Builder matchNwSrc(IntIPv4 addr, int length) {
            rule.setNwSrcAddress(addr.toString());
            rule.setNwSrcLength(length);
            return this;
        }

        public Builder matchInPort(UUID vportId) {
            rule.setInPorts(new UUID[]{vportId});
            return this;
        }

        public Builder matchOutPort(UUID vportId) {
            rule.setOutPorts(new UUID[]{vportId});
            return this;
        }

        public Builder matchPortGroup(UUID groupId) {
            rule.setPortGroup(groupId);
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

    public void delete() {
        mgmt.delete(rule.getUri());
    }

}
