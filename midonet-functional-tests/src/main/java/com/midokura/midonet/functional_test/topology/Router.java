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
            RuleChain.Builder rcBuilder = new RuleChain.Builder(mgmt, tenant);
            rcBuilder.setName(router.getName() + PRE_ROUTING);
            RuleChain inChain = rcBuilder.build();
            router.setInboundFilter(inChain.chain.getId());
            rcBuilder = new RuleChain.Builder(mgmt, tenant);
            rcBuilder.setName(router.getName() + POST_ROUTING);
            RuleChain outChain = rcBuilder.build();
            router.setOutboundFilter(outChain.chain.getId());
            return new Router(
                    mgmt, mgmt.addRouter(tenant, router), inChain, outChain);
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

    public MaterializedRouterPort.VMPortBuilder addVmPort() {
        return new MaterializedRouterPort.VMPortBuilder(mgmt, dto);
    }

    public MaterializedRouterPort.GWPortBuilder addGwPort() {
        return new MaterializedRouterPort.GWPortBuilder(mgmt, dto);
    }

    public MaterializedRouterPort.VPNPortBuilder addVpnPort() {
        return new MaterializedRouterPort.VPNPortBuilder(mgmt, dto);
    }
    
    public LogicalRouterPort.Builder addLinkPort() {
        return new LogicalRouterPort.Builder(mgmt, dto);
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
                .matchNwDst(pubAddr, 32)
                .matchInPort(uplinkId).build();
        // Add a SNAT to the post-routing chain.
        postRoutingChain.addRule().setSnat(pubAddr, 0)
                .matchNwSrc(privAddr, 32)
                .matchOutPort(uplinkId).build();
    }

    public void dropTrafficTo(String addr, int length) {
        preRoutingChain.addRule()
                .matchNwDst(IntIPv4.fromString(addr), length)
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
