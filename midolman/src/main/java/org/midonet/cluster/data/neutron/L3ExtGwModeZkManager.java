/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Function;
import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.rules.ForwardNatRule;
import org.midonet.midolman.rules.NatRule;
import org.midonet.midolman.rules.ReverseNatRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.packets.IPv4Subnet;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public class L3ExtGwModeZkManager extends BaseZkManager {

    private final NetworkZkManager networkZkManager;
    private final ProviderRouterZkManager providerRouterZkManager;
    private final PortZkManager portZkManager;
    private final RouteZkManager routeZkManager;
    private final RouterZkManager routerZkManager;
    private final RuleZkManager ruleZkManager;

    @Inject
    public L3ExtGwModeZkManager(ZkManager zk, PathBuilder paths,
                                Serializer serializer,
                                NetworkZkManager networkZkManager,
                                ProviderRouterZkManager providerRouterZkManager,
                                PortZkManager portZkManager,
                                RouteZkManager routeZkManager,
                                RouterZkManager routerZkManager,
                                RuleZkManager ruleZkManager) {
        super(zk, paths, serializer);
        this.networkZkManager = networkZkManager;
        this.providerRouterZkManager = providerRouterZkManager;
        this.portZkManager = portZkManager;
        this.routeZkManager = routeZkManager;
        this.routerZkManager = routerZkManager;
        this.ruleZkManager = ruleZkManager;
    }

    public static abstract class SnatMatcher
            implements Function<Rule, Boolean> {

        abstract protected Class<? extends NatRule> clazz();
        abstract protected boolean containsPort(Rule rule);
        final protected UUID uplinkPort;

        public SnatMatcher(UUID uplinkPortId) {
            this.uplinkPort = uplinkPortId;
        }

        @Override
        public Boolean apply(@Nullable Rule rule) {
            return rule.getClass().equals(this.clazz()) && containsPort(rule);
        }
    }

    public static class ForwardSnatMatcher extends SnatMatcher {

        public ForwardSnatMatcher(UUID uplinkPortId) {
            super(uplinkPortId);
        }

        @Override
        protected Class<? extends NatRule> clazz() {
            return ForwardNatRule.class;
        }

        @Override
        protected boolean containsPort(Rule rule) {
            return rule.getCondition().containsOutPort(uplinkPort);
        }
    }

    public static class ReverseSnatMatcher extends SnatMatcher {

        public ReverseSnatMatcher(UUID uplinkPortId) {
            super(uplinkPortId);
        }

        @Override
        protected Class<? extends NatRule> clazz() {
            return ReverseNatRule.class;
        }

        @Override
        protected boolean containsPort(Rule rule) {
            return rule.getCondition().containsInPort(uplinkPort);
        }
    }

    public void prepareCreateGatewayPort(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {
        // Create a port on the provider router
        UUID prId = providerRouterZkManager.getId();
        RouterPortConfig rpCfg = new RouterPortConfig(prId,
                ProviderRouter.LL_CIDR, ProviderRouter.LL_GW_IP_1, true);
        ops.addAll(portZkManager.prepareCreate(port.id, rpCfg));
    }

    public void prepareDeleteGatewayPort(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        PortConfig p = portZkManager.get(port.id);
        portZkManager.prepareDelete(ops, p, true);

        // Update the Neutron router to have gwPortId set to null.
        // This should also delete routes for these ports.
        String path = paths.getNeutronRouterPath(p.device_id);
        Router r = serializer.deserialize(zk.get(path), Router.class);
        r.gwPortId = null;
        ops.add(zk.getSetDataOp(path, serializer.serialize(r)));

        // TODO: Deleting ports does not delete rules referencing them.
        // Remove all the NAT rules referencing this port from the tenant
        // router.
        PortConfig peer = portZkManager.get(p.peerId);
        RouterConfig rCfg = routerZkManager.get(peer.device_id);
        ruleZkManager.prepareDeleteRules(ops, rCfg.inboundFilter,
                new ReverseSnatMatcher(peer.id));
        ruleZkManager.prepareDeleteRules(ops, rCfg.outboundFilter,
                new ForwardSnatMatcher(peer.id));
    }

    private UUID prepareLinkToGwRouter(List<Op> ops, UUID rId, UUID gwPortId)
            throws SerializationException, StateAccessException {

        Port gwPort = networkZkManager.getPort(gwPortId);
        return prepareLinkToGwRouter(ops, rId, gwPort);
    }

    private UUID prepareLinkToGwRouter(List<Op> ops, UUID rId, Port gwPort)
            throws SerializationException, StateAccessException {

        // Create a port on the provider router
        UUID prId = providerRouterZkManager.getId();
        RouterPortConfig rpCfg = (RouterPortConfig) portZkManager.get(
                gwPort.id);

        // Add a route to this gateway port on the provider router
        Route rtToRouter = Route.nextHopPortRoute(new IPv4Subnet(),
                gwPort.firstIpv4Subnet(), gwPort.id, null, 100, prId);
        ops.addAll(routeZkManager.prepareRouteCreate(UUID.randomUUID(),
                rtToRouter, true, rpCfg));

        // Create a port on the tenant router
        UUID rpId = UUID.randomUUID();
        RouterPortConfig rpCfgPeer = new RouterPortConfig(rId,
                ProviderRouter.LL_CIDR, ProviderRouter.LL_GW_IP_2, true);
        ops.addAll(portZkManager.prepareCreate(rpId, rpCfgPeer));

        // Link the routers
        portZkManager.prepareLink(ops, gwPort.id, rpId, rpCfg, rpCfgPeer);

        // Add default route
        Route defRt = Route.defaultRoute(rpId, 100, rId);
        ops.addAll(routeZkManager.prepareRouteCreate(UUID.randomUUID(), defRt,
                true, rpCfgPeer));

        return rpId;
    }

    public void prepareCreateGatewayRouter(List<Op> ops, Router router)
            throws SerializationException, StateAccessException {

        if (router.gwPortId == null) {
            return;
        }

        // Get the gateway port info.  We can assume that there is one
        // IP address assigned to this, which is reserved for gw IP.
        Port gwPort = networkZkManager.getPort(router.gwPortId);

        // Link the router to the provider router and set up routes.
        UUID uplinkPortId = prepareLinkToGwRouter(ops, router.id, gwPort);

        if (router.snatEnabled()) {

            RouterConfig cfg = routerZkManager.get(router.id);

            // Get the SNAT rules necessary for the chains.
            // The Neutron port ID is used to map the SNAT rules to it,
            // so that when the port is removed, the correct NAT rules
            // would be deleted.
            Rule revNatRule = ReverseNatRule.reverseSnatRule(cfg.inboundFilter,
                    uplinkPortId, gwPort.firstIpv4Addr());
            Rule natRule = ForwardNatRule.dynamicSnatRule(cfg.outboundFilter,
                    uplinkPortId, gwPort.firstIpv4Addr());

            ruleZkManager.prepareUpdateRuleInNewChain(ops, revNatRule);
            ruleZkManager.prepareUpdateRuleInNewChain(ops, natRule);
        }
    }

    public void prepareUpdateGatewayRouter(List<Op> ops, final Router router)
            throws SerializationException, StateAccessException,
            org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {

        final RouterConfig config = routerZkManager.get(router.id);

        UUID uplinkPortId = null;
        if (router.gwPortId != null) {
            // Gateway port was created, updated or unchanged.  If the case of
            // create or update, the gateway port is still not yet linked to
            // the tenant router.
            PortConfig pConfig = portZkManager.get(router.gwPortId);
            if (pConfig.peerId == null) {
                // Need to link provider router and the tenant router.
                uplinkPortId = prepareLinkToGwRouter(ops, router.id,
                        router.gwPortId);
            } else {
                uplinkPortId = pConfig.peerId;
            }
        }

        // If the uplink port ID is null, then the gateway port along with
        // its assocaited SNAT rules either never existed or were deleted
        // in deletePort earlier.  In that case, there is no action taken since
        // SNAT rule cannot be created.
        if (uplinkPortId != null) {

            // If gateway link exists, then determine whether SNAT is enabled.
            // If it is, then make sure that the right SNAT rules are included
            // in the chains.  Delete all SNAT rules if SNAT is disabled.
            Rule forwardRule = null, reverseRule = null;
            if (router.snatEnabled()) {
                Port p = networkZkManager.getPort(router.gwPortId);
                forwardRule = ForwardNatRule.dynamicSnatRule(
                        config.outboundFilter, uplinkPortId, p.firstIpv4Addr());
                reverseRule = ReverseNatRule.reverseSnatRule(
                        config.inboundFilter, uplinkPortId, p.firstIpv4Addr());
            }

            ruleZkManager.prepareDeleteRules(ops, config.inboundFilter,
                    new ReverseSnatMatcher(uplinkPortId), reverseRule);
            ruleZkManager.prepareDeleteRules(ops, config.outboundFilter,
                    new ForwardSnatMatcher(uplinkPortId), forwardRule);
        }
    }
}
