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
            // Create pre- and post-filtering rule-chains for this router.
            RuleChain.Builder rcBuilder1 = new RuleChain.Builder(mgmt, tenant);
            rcBuilder1.setName(router.getName() + PRE_ROUTING);
            RuleChain.Builder rcBuilder2 = new RuleChain.Builder(mgmt, tenant);
            rcBuilder2.setName(router.getName() + POST_ROUTING);
            return new Router(mgmt, mgmt.addRouter(tenant, router),
                    rcBuilder1.build(), rcBuilder2.build());
        }
    }

    public final static String PRE_ROUTING = "pre_routing";
    public final static String POST_ROUTING = "post_routing";

    MidolmanMgmt mgmt;
    DtoRouter dto;
    RuleChain preRoutingChain;
    RuleChain postRoutingChain;

    Router(MidolmanMgmt mgmt, DtoRouter router, RuleChain preRoutingChain,
           RuleChain postRoutingChain) {
        this.mgmt = mgmt;
        this.dto = router;
        this.preRoutingChain = preRoutingChain;
        this.postRoutingChain = postRoutingChain;
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
        mgmt.delete(dto.getUri());
        preRoutingChain.delete();
        postRoutingChain.delete();
    }

    public void addFloatingIp(IntIPv4 privAddr, IntIPv4 pubAddr, UUID uplinkId) {
        // Add a DNAT to the pre-routing chain.
        preRoutingChain.addRule().setDnat(privAddr, 0)
                .setMatchNwDst(pubAddr, 32)
                .setMatchInPort(uplinkId).build();
        // Add a SNAT to the post-routing chain.
        postRoutingChain.addRule().setSnat(pubAddr, 0)
                .setMatchNwSrc(privAddr, 32)
                .setMatchOutPort(uplinkId).build();
    }

    public void dropTrafficTo(String addr, int length) {
        preRoutingChain.addRule()
                .setMatchNwDst(IntIPv4.fromString(addr), length)
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
