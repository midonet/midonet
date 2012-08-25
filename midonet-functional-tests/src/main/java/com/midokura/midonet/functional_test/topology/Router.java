package com.midokura.midonet.functional_test.topology;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.midokura.packets.IntIPv4;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
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
            return new Router(
                    mgmt, tenant, mgmt.addRouter(tenant, router));
        }
    }

    public final static String PRE_ROUTING = "pre_routing";
    public final static String POST_ROUTING = "post_routing";

    MidolmanMgmt mgmt;
    DtoTenant tenant;
    DtoRouter dto;
    RuleChain preRoutingChain;
    RuleChain postRoutingChain;
    Map<IntIPv4, Rule> floatingIpDnats = new HashMap<IntIPv4, Rule>();
    Map<IntIPv4, Rule> floatingIpSnats = new HashMap<IntIPv4, Rule>();

    Router(MidolmanMgmt mgmt, DtoTenant tenant, DtoRouter router) {
        this.mgmt = mgmt;
        this.tenant = tenant;
        this.dto = router;
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

    public void addFilters() {
        // Create pre- and post-filtering rule-chains for this router.
        RuleChain.Builder rcBuilder = new RuleChain.Builder(mgmt, tenant);
        rcBuilder.setName(dto.getName() + PRE_ROUTING);
        preRoutingChain = rcBuilder.build();
        dto.setInboundFilterId(preRoutingChain.chain.getId());
        rcBuilder = new RuleChain.Builder(mgmt, tenant);
        rcBuilder.setName(dto.getName() + POST_ROUTING);
        postRoutingChain = rcBuilder.build();
        dto.setOutboundFilterId(postRoutingChain.chain.getId());
        mgmt.updateRouter(dto);
    }

    public void removeFilters() {
        dto.setInboundFilterId(null);
        dto.setOutboundFilterId(null);
        mgmt.updateRouter(dto);
    }

    public void removeFloatingIp(IntIPv4 floatingIP) {
        Rule r = floatingIpDnats.get(floatingIP);
        if (null != r)
            r.delete();
        r = floatingIpSnats.get(floatingIP);
        if (null != r)
            r.delete();
    }

    public void addFloatingIp(IntIPv4 privAddr, IntIPv4 floatingIP,
                              UUID uplinkId) {
        // Add a DNAT to the pre-routing chain.
        floatingIpDnats.put(floatingIP,
                preRoutingChain.addRule().setDnat(privAddr, 0)
                        .matchNwDst(floatingIP, 32)
                        .matchInPort(uplinkId).build());
        // Add a SNAT to the post-routing chain.
        floatingIpSnats.put(floatingIP,
                postRoutingChain.addRule().setSnat(floatingIP, 0)
                        .matchNwSrc(privAddr, 32)
                        .matchOutPort(uplinkId).build());
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
