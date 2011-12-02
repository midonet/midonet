package com.midokura.midonet.smoketest.topology;

import java.util.UUID;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mgmt.DtoRule;
import com.midokura.midonet.smoketest.mgmt.DtoTenant;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class Router {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoTenant tenant;
        DtoRouter router;

        public Builder(MidolmanMgmt mgmt, DtoTenant tenant) {
            this.mgmt = mgmt;
            this.tenant = tenant;
            this.router = new DtoRouter();
        }

        public Builder setName(String name) {
            router.setName(name);
            return this;
        }

        public Router build() {
            if (null == router.getName() || router.getName().isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "router with a null or empty name.");
            return new Router(mgmt, mgmt.addRouter(tenant, router));
        }
    }

    MidolmanMgmt mgmt;
    DtoRouter dto;

    Router(MidolmanMgmt mgmt, DtoRouter router) {
        this.mgmt = mgmt;
        this.dto = router;
    }

    public VPortBuilder addPort(OpenvSwitchDatabaseConnection ovsdb) {
        return new VPortBuilder(ovsdb, mgmt, dto);
    }

    public MidoPort.VMPortBuilder addVmPort() {
        return new MidoPort.VMPortBuilder(mgmt, dto);
    }

    public MidoPort.GWPortBuilder addGwPort() {
        return new MidoPort.GWPortBuilder(mgmt, dto);
    }

    public MidoPort.VPNPortBuilder addVpnPort() {
        return new MidoPort.VPNPortBuilder(mgmt, dto);
    }

    public PeerRouterLink.Builder addRouterLink() {
        return new PeerRouterLink.Builder(mgmt, dto);
    }

    public void delete() {

    }

    public void addFloatingIp(IntIPv4 privAddr, IntIPv4 pubAddr, UUID uplinkId) {
        // Add a DNAT to the pre-routing chain.
        RuleChain chain = getRuleChain(RuleChain.PRE_ROUTING);
        chain.addRule().setDnat(privAddr, 0).setMatchNwDst(pubAddr, 32)
                .setMatchInPort(uplinkId).build();
        // Add a SNAT to the post-routing chain.
        chain = getRuleChain(RuleChain.POST_ROUTING);
        chain.addRule().setSnat(pubAddr, 0).setMatchNwSrc(privAddr, 32)
                .setMatchOutPort(uplinkId).build();
    }

    public RuleChain.Builder addRuleChain() {
        return new RuleChain.Builder(mgmt, dto);
    }

    public RuleChain getRuleChain(String name) {
        return new RuleChain(mgmt, mgmt.getRuleChain(dto, name));
    }

    public void dropTrafficTo(String addr, int length) {
        RuleChain chain = getRuleChain(RuleChain.PRE_ROUTING);
        chain.addRule().setMatchNwDst(IntIPv4.fromString(addr), length)
                .setSimpleType(DtoRule.Drop).build();
    }
}
