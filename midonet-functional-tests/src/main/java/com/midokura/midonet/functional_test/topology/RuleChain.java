package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class RuleChain {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoTenant tenant;
        DtoRuleChain chain;

        public Builder(MidolmanMgmt mgmt, DtoTenant tenant) {
            this.mgmt = mgmt;
            this.tenant = tenant;
            this.chain = new DtoRuleChain();
        }

        public Builder setName(String name) {
            chain.setName(name);
            return this;
        }

        public RuleChain build() {
            if (null == chain.getName() || chain.getName().isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "rule chain with a null or empty name.");
            return new RuleChain(mgmt, mgmt.addRuleChain(tenant, chain));
        }
    }

    private MidolmanMgmt mgmt;
    public DtoRuleChain chain;

    RuleChain(MidolmanMgmt mgmt, DtoRuleChain chain) {
        this.mgmt = mgmt;
        this.chain = chain;
    }

    public void delete() {
        mgmt.delete(chain.getUri());
    }

    public Rule.Builder addRule() {
        return new Rule.Builder(mgmt, chain);
    }
}
