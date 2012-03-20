package com.midokura.midonet.functional_test.topology;

import java.util.UUID;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

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

    public RouterPort.VMPortBuilder addVmPort() {
        return new RouterPort.VMPortBuilder(mgmt, dto);
    }

    public RouterPort.GWPortBuilder addGwPort() {
        return new RouterPort.GWPortBuilder(mgmt, dto);
    }

    public RouterPort.VPNPortBuilder addVpnPort() {
        return new RouterPort.VPNPortBuilder(mgmt, dto);
    }

    public PeerRouterLink.Builder addRouterLink() {
        return new PeerRouterLink.Builder(mgmt, dto);
    }

    public BridgeRouterLink addBridgeRouterLink(
            Bridge br, IntIPv4 subnet) {
        return new BridgeRouterLink.Builder(mgmt, dto, br, subnet).build();
    }

    public String getName() {
        return dto.getName();
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

    public Route[] getRoutes() {
        DtoRoute[] dtoRoutes = mgmt.getRoutes(dto);

        Route[] routes = new Route[dtoRoutes.length];
        for (int i = 0, dtoRoutesLength = dtoRoutes.length; i < dtoRoutesLength; i++) {
            routes[i] = new Route(mgmt, dtoRoutes[i]);
        }

        return routes;
    }
}
