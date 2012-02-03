package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class RuleChain {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoRouter router;
        DtoRuleChain chain;

        public Builder(MidolmanMgmt mgmt, DtoRouter router) {
            this.mgmt = mgmt;
            this.router = router;
            this.chain = new DtoRuleChain();
        }

        public Builder setName(String name) {
            chain.setName(name);
            return this;
        }

        public Builder setTable(DtoRuleChain.ChainTable table) {
            chain.setTable(table);
            return this;
        }

        public RuleChain build() {
            if (null == chain.getName() || chain.getName().isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "rule chain with a null or empty name.");
            return new RuleChain(mgmt, mgmt.addRuleChain(router, chain));
        }
    }

    public final static String NAT_TABLE = "nat";
    public final static String PRE_ROUTING = "pre_routing";
    public final static String POST_ROUTING = "post_routing";

    private MidolmanMgmt mgmt;
    private DtoRuleChain chain;

    RuleChain(MidolmanMgmt mgmt, DtoRuleChain chain) {
        this.mgmt = mgmt;
        this.chain = chain;
    }

    public Rule.Builder addRule() {
        return new Rule.Builder(mgmt, chain);
    }
}
