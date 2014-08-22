/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.cluster.data.Rule;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.zkManagers.*;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class L3ZkManager extends BaseZkManager {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(L3ZkManager.class);

    private final NetworkZkManager networkZkManager;
    private final ProviderRouterZkManager providerRouterZkManager;
    private final PortZkManager portZkManager;
    private final RouteZkManager routeZkManager;
    private final RouterZkManager routerZkManager;
    private final ChainZkManager chainZkManager;
    private final RuleZkManager ruleZkManager;

    @Inject
    public L3ZkManager(ZkManager zk,
                       PathBuilder paths,
                       Serializer serializer,
                       NetworkZkManager networkZkManager,
                       ProviderRouterZkManager providerRouterZkManager,
                       ChainZkManager chainZkManager,
                       PortZkManager portZkManager,
                       RouteZkManager routeZkManager,
                       RouterZkManager routerZkManager,
                       RuleZkManager ruleZkManager) {
        super(zk, paths, serializer);
        this.networkZkManager = networkZkManager;
        this.providerRouterZkManager = providerRouterZkManager;
        this.chainZkManager = chainZkManager;
        this.portZkManager = portZkManager;
        this.routeZkManager = routeZkManager;
        this.routerZkManager = routerZkManager;
        this.ruleZkManager = ruleZkManager;
    }

    public Router getRouter(UUID routerId) throws StateAccessException,
        SerializationException {

        String path = paths.getNeutronRouterPath(routerId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), Router.class);
    }

    public List<Router> getRouters() throws StateAccessException,
        SerializationException {

        String path = paths.getNeutronRoutersPath();
        Set<UUID> routerIds = getUuidSet(path);

        List<Router> routers = new ArrayList<>();
        for (UUID routerId : routerIds) {
            routers.add(getRouter(routerId));
        }

        return routers;
    }

    private UUID prepareCreateChain(List<Op> ops, String name,
                                    String tenantId)
        throws SerializationException, StateAccessException {
        UUID chainId = UUID.randomUUID();
        ChainConfig chainConfig = new ChainConfig(name);
        chainConfig.setTenantId(tenantId);
        chainZkManager.prepareCreate(ops, chainId, chainConfig);
        return chainId;
    }

    public void prepareCreateRouter(List<Op> ops, Router router)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {

        UUID preChainId = prepareCreateChain(ops, router.preRouteChainName(),
                                             router.tenantId);
        UUID postChainId = prepareCreateChain(ops, router.postRouteChainName(),
                                              router.tenantId);

        RouterConfig config = new RouterConfig(router, preChainId, postChainId);
        config.setTenantId(router.tenantId);
        ops.addAll(routerZkManager.prepareRouterCreate(router.id, config));

        // The path to 'ref' directory gets created twice, once in
        // prepareChainCreate and then again in prepareRouterCreate.
        // Remove the last occurrence.
        zk.removeLastOp(ops, paths.getChainBackRefsPath(postChainId));
        zk.removeLastOp(ops, paths.getChainBackRefsPath(preChainId));

        String path = paths.getNeutronRouterPath(router.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(router)));

        if (router.gwPortId != null) {

            // Gateway port ID is set, which means that gateway is set at the
            // time of router creation.
            prepareCreateGatewayRouter(ops, router, preChainId, postChainId);
        }
    }

    public void prepareDeleteRouter(List<Op> ops, UUID id)
            throws SerializationException, StateAccessException {

        RouterConfig config = routerZkManager.get(id);
        if (config == null)
            return;

        ops.addAll(chainZkManager.prepareDelete(config.inboundFilter));
        ops.addAll(chainZkManager.prepareDelete(config.outboundFilter));

        ops.addAll(routerZkManager.prepareRouterDelete(id));

        // The path to 'ref' directory gets deleted twice, once in
        // prepareChainDelete and then again in prepareRouterDelete.
        // Remove the last occurrence.
        String inRefPath = paths.getChainBackRefPath(config.inboundFilter,
                ResourceType.ROUTER.toString(), id);
        String outRefPath = paths.getChainBackRefPath(config.outboundFilter,
                ResourceType.ROUTER.toString(), id);
        zk.removeLastOp(ops, inRefPath);
        zk.removeLastOp(ops, outRefPath);

        ops.add(zk.getDeleteOp(paths.getNeutronRouterPath(id)));
    }

    public void prepareUpdateRouter(List<Op> ops, Router router)
            throws SerializationException, StateAccessException,
            org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {

        prepareUpdateGatewayRouter(ops, router);

        RouterConfig config = routerZkManager.get(router.id);
        config.name = router.name;
        config.adminStateUp = router.adminStateUp;
        List<Op> updateOps = routerZkManager.prepareUpdate(router.id,
                config);
        if (updateOps != null) {
            ops.addAll(updateOps);
        }

        // Update the neutron router config
        ops.add(zk.getSetDataOp(paths.getNeutronRouterPath(router.id),
                serializer.serialize(router)));
    }

    public void prepareCreateRouterInterface(List<Op> ops, RouterInterface rInt)
            throws SerializationException, StateAccessException {

        Port port = networkZkManager.getPort(rInt.portId);
        Subnet subnet = networkZkManager.getSubnet(rInt.subnetId);

        BridgePortConfig bpConfig =
                (BridgePortConfig) portZkManager.get(port.id);
        if (!port.isRouterInterface()) {
            // Update this port to the correct port type.  This happens when
            // a non-RouterInterface port was specified to be used to create
            // RouterInterface port.
            port.deviceId = rInt.id.toString();
            port.deviceOwner = DeviceOwner.ROUTER_INTF;
            networkZkManager.prepareUpdateNeutronPort(ops, port);

            if (bpConfig.inboundFilter != null) {
                ops.addAll(chainZkManager.prepareDelete(
                    bpConfig.inboundFilter));
            }

            if (bpConfig.outboundFilter != null) {
                ops.addAll(chainZkManager.prepareDelete(
                    bpConfig.outboundFilter));
            }

            networkZkManager.prepareDeleteDhcpHostEntries(ops, port);
        }

        // For IPv6, this is not supported
        if (!subnet.isIpv4()) return;

        // Create a router port
        UUID rpId = UUID.randomUUID();
        RouterPortConfig rpConfig = new RouterPortConfig(rInt.id,
                subnet.cidrAddressInt(), subnet.cidrAddressLen(),
                subnet.gwIpInt(), true);
        ops.addAll(portZkManager.prepareCreate(rpId, rpConfig));

        // Link them
        portZkManager.prepareLink(ops, port.id, rpId, bpConfig, rpConfig);

        // Add a route to this subnet
        routeZkManager.preparePersistPortRouteCreate(ops, UUID.randomUUID(),
                new IPv4Subnet(0, 0), subnet.ipv4Subnet(), rpId, null, rInt.id,
                rpConfig);


        Port dPort = networkZkManager.getDhcpPort(subnet.networkId);
        if (dPort != null && dPort.hasIp()) {

            prepareAddMetadataServiceRoute(ops, rInt.id, rpId,
                    dPort.firstIpv4Addr(), rpConfig);
        }
    }

    private void prepareAddMetadataServiceRoute(List<Op> ops, UUID routerId,
                                                UUID routerPortId,
                                                IPv4Addr nextHopAddr,
                                                RouterPortConfig rpCfg)
            throws StateAccessException, SerializationException {

        // Add a route for the metadata server.
        // Not all VM images supports DHCP option 121.  Add a route for the
        // Metadata server in the router to forward the packet to the bridge
        // that will send them to the Metadata Proxy.
        routeZkManager.preparePersistPortRouteCreate(ops, UUID.randomUUID(),
            new IPv4Subnet(0, 0), MetaDataService.IPv4_SUBNET, routerPortId,
            nextHopAddr, routerId, rpCfg);
    }

    private void prepareRemoveMetadataServiceRoute(List<Op> ops, UUID routerId,
                                                   IPv4Addr dhcpAddr)
        throws StateAccessException, SerializationException {
        routeZkManager.prepareRoutesDelete(ops, routerId,
            MetaDataService.IPv4_SUBNET, dhcpAddr.addr());
    }

    public void prepareAddMetadataServiceRoute(List<Op> ops, Port dhcpPort)
            throws StateAccessException, SerializationException {

        Subnet subnet = networkZkManager.getSubnet(dhcpPort.firstSubnetId());
        // no need to add the metadata service route if there is no gateway
        // associated with the subnet.
        if (subnet.gatewayIpAddr() != null) {
            RouterPortConfig rpCfg =
                portZkManager.findGatewayRouterPortFromBridge(
                    dhcpPort.networkId, subnet.gatewayIpAddr());
            // If rpCfg is null, it means a router interface was not yet
            // created on this subnet.
            if (rpCfg != null) {
                prepareAddMetadataServiceRoute(ops, rpCfg.device_id, rpCfg.id,
                    dhcpPort.firstIpv4Addr(), rpCfg);
            }
        }
    }

    public void prepareRemoveMetadataServiceRoute(List<Op> ops, Port dhcpPort)
        throws StateAccessException, SerializationException {
        Subnet subnet = networkZkManager.getSubnet(dhcpPort.firstSubnetId());
        if (subnet != null && subnet.gatewayIpAddr() != null) {
            RouterPortConfig rpCfg =
                portZkManager.findGatewayRouterPortFromBridge(
                    dhcpPort.networkId, subnet.gatewayIpAddr());
            if (rpCfg != null) {
                prepareRemoveMetadataServiceRoute(ops, rpCfg.device_id,
                    dhcpPort.firstIpv4Addr());
            }
        }
    }

    public void prepareCreateProviderRouterGwPort(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {
        // Create a port on the provider router
        UUID prId = providerRouterZkManager.getId();
        RouterPortConfig rpCfg = new RouterPortConfig(prId,
                ProviderRouter.LL_CIDR, ProviderRouter.LL_GW_IP_1, true);
        ops.addAll(portZkManager.prepareCreate(port.id, rpCfg));
    }

    public void prepareDeleteGatewayPort(List<Op> ops, Port port)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {

        networkZkManager.prepareDeletePortConfig(ops, port.id);

        // Update the Neutron router to have gwPortId set to null.
        // This should also delete routes for these ports.
        Router r = getRouter(port.deviceIdUuid());
        r.gwPortId = null;
        ops.add(zk.getSetDataOp(paths.getNeutronRouterPath(r.id),
                serializer.serialize(r)));

        // Note: Deleting ports does not delete rules referencing them.
        // Remove all the NAT rules referencing this port from the tenant
        // router.
        PortConfig peer = portZkManager.getPeerPort(port.id);
        RouterConfig rCfg = routerZkManager.get(peer.device_id);
        ruleZkManager.prepareDeleteSourceNatRules(ops, rCfg.inboundFilter,
            rCfg.outboundFilter, port.firstIpv4Addr());
    }

    private UUID prepareLinkToGwRouter(List<Op> ops, UUID rId, UUID gwPortId)
            throws SerializationException, StateAccessException {

        Port gwPort = networkZkManager.getPort(gwPortId);
        return prepareLinkToGwRouter(ops, rId, gwPort);
    }

    private UUID prepareLinkToGwRouter(List<Op> ops, UUID rId, Port gwPort)
            throws SerializationException, StateAccessException {
        return prepareLinkToGwRouter(ops, rId, gwPort.id,
                gwPort.firstIpv4Subnet());
    }

    private UUID prepareLinkToGwRouter(List<Op> ops, UUID rId, UUID portId,
                                       IPv4Subnet cidr)
            throws SerializationException, StateAccessException {

        UUID prId = providerRouterZkManager.getId();

        // Create a port on the tenant router
        RouterPortConfig rpCfgPeer = new RouterPortConfig(rId,
                ProviderRouter.LL_CIDR, ProviderRouter.LL_GW_IP_2, true);
        rpCfgPeer.id = UUID.randomUUID();
        ops.addAll(portZkManager.prepareCreate(rpCfgPeer.id, rpCfgPeer));

        // Get the tenant router port and link the routers
        RouterPortConfig rpCfg = (RouterPortConfig) portZkManager.get(portId);
        portZkManager.prepareLink(ops, portId, rpCfgPeer.id, rpCfg,
                rpCfgPeer);

        // Add a route to this gateway port on the provider router
        routeZkManager.preparePersistPortRouteCreate(ops, prId,
                new IPv4Subnet(0, 0), cidr, rpCfg, null);

        routeZkManager.preparePersistDefaultRouteCreate(ops, rId, rpCfgPeer);

        return rpCfgPeer.id;
    }

    private void prepareCreateGatewayRouter(List<Op> ops, Router router,
                                            UUID inboundChainId,
                                            UUID outboundChainId)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {

        // Get the gateway port info.  We can assume that there is one
        // IP address assigned to this, which is reserved for gw IP.
        Port gwPort = networkZkManager.getPort(router.gwPortId);
        gwPort.deviceId = router.id.toString();
        ops.add(zk.getSetDataOp(paths.getNeutronPortPath(gwPort.id),
                serializer.serialize(gwPort)));

        // Link the router to the provider router and set up routes.
        UUID portId = prepareLinkToGwRouter(ops, router.id, gwPort);

        if (router.snatEnabled()) {
            ruleZkManager.prepareCreateSourceNatRules(ops,
                inboundChainId, outboundChainId, portId,
                gwPort.firstIpv4Addr());
        }
    }

    private void prepareUpdateGatewayRouter(List<Op> ops, final Router router)
            throws SerializationException, StateAccessException,
            org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {

        final RouterConfig config = routerZkManager.get(router.id);

        UUID portId = null;
        if (router.gwPortId != null) {
            // Gateway port was created, updated or unchanged.  If the case of
            // create or update, the gateway port is still not yet linked to
            // the tenant router.
            PortConfig pConfig = portZkManager.get(router.gwPortId);
            if (pConfig.peerId == null) {
                // Need to link provider router and the tenant router.
                portId = prepareLinkToGwRouter(ops, router.id, router.gwPortId);
            } else {
                portId = pConfig.peerId;
            }
        }

        // If the uplink port ID is null, then the gateway port along with
        // its associated SNAT rules either never existed or were deleted
        // in deletePort earlier.  In that case, there is no action taken since
        // SNAT rule cannot be created.
        if (portId != null) {

            // If gateway link exists, then determine whether SNAT is enableod.
            // If it is, then make sure that the right SNAT rules are included
            // in the chains.  Delete all SNAT rules if SNAT is disabled.
            Port gwPort = networkZkManager.getPort(router.gwPortId);
            gwPort.deviceId = router.id.toString();
            ops.add(zk.getSetDataOp(paths.getNeutronPortPath(gwPort.id),
                    serializer.serialize(gwPort)));
            if (router.snatEnabled()) {
                if (!ruleZkManager.snatRuleExists(config.outboundFilter,
                    gwPort.firstIpv4Addr())) {
                    ruleZkManager.prepareCreateSourceNatRules(ops,
                        config.inboundFilter, config.outboundFilter, portId,
                        gwPort.firstIpv4Addr());
                }
            } else {
                ruleZkManager.prepareDeleteSourceNatRules(ops,
                    config.inboundFilter, config.outboundFilter,
                    gwPort.firstIpv4Addr());
            }
        }
    }

    private FloatingIp findFloatingIpByPort(final UUID portId)
            throws StateAccessException, SerializationException {
        return findFloatingIp(new Function<FloatingIp, Boolean>() {
            @Override
            public Boolean apply(FloatingIp floatingIp) {
                if (floatingIp == null)
                    throw new IllegalArgumentException(
                        "floatingIp must not be null");
                return Objects.equal(floatingIp.portId, portId);
            }
        });
    }

    private FloatingIp findFloatingIp(
            Function<FloatingIp, Boolean> matcher)
            throws SerializationException, StateAccessException {
        List<FloatingIp> fips = getFloatingIps();
        for (FloatingIp fip : fips) {
            if (matcher.apply(fip)) {
                return fip;
            }
        }
        return null;
    }

    public void prepareCreateFloatingIp(List<Op> ops, FloatingIp floatingIp)
            throws SerializationException, StateAccessException,
            org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {
        String path = paths.getNeutronFloatingIpPath(floatingIp.id);
        ops.add(zk.getPersistentCreateOp(path,
                        serializer.serialize(floatingIp)));
        if (floatingIp.isAssociated()) {
            prepareAssociateFloatingIp(ops, floatingIp);
        }
    }

    public FloatingIp getFloatingIp(UUID floatingIpId)
            throws StateAccessException, SerializationException {

        String path = paths.getNeutronFloatingIpPath(floatingIpId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), FloatingIp.class);
    }

    public List<FloatingIp> getFloatingIps()
            throws StateAccessException, SerializationException {

        String path = paths.getNeutronFloatingIpsPath();
        Set<String> floatingIpIds = zk.getChildren(path);

        List<FloatingIp> floatingIps = new ArrayList<>();
        for (String floatingIpId : floatingIpIds) {
            floatingIps.add(getFloatingIp(UUID.fromString(floatingIpId)));
        }

        return floatingIps;
    }

    public void prepareDeleteFloatingIp(List<Op> ops, FloatingIp fip)
        throws StateAccessException, SerializationException {
        Preconditions.checkNotNull(fip);
        Port port = networkZkManager.getPort(fip.portId);
        if (port != null) {
            prepareDisassociateFloatingIp(ops, port);
        }
        String path = paths.getNeutronFloatingIpPath(fip.id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareDeleteFloatingIp(List<Op> ops, UUID floatingIpId)
        throws StateAccessException, SerializationException {
        FloatingIp fip = getFloatingIp(floatingIpId);
        if (fip != null) {
            prepareDeleteFloatingIp(ops, fip);
        }
    }

    private void prepareAssociateFloatingIp(List<Op> ops, FloatingIp fip)
            throws SerializationException, StateAccessException,
            org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {

        UUID prId = providerRouterZkManager.getId();

        // Find the GW port
        RouterPortConfig gwPort =
            portZkManager.findFirstRouterPortByPeer(fip.routerId, prId);

        // Add a route to this gateway port on the provider router
        RouterPortConfig prPortCfg =
            (RouterPortConfig) portZkManager.get(gwPort.peerId);
        routeZkManager.preparePersistPortRouteCreate(ops, prId,
            new IPv4Subnet(0, 0), fip.floatingIpv4Subnet(), prPortCfg, null);

        // Add NAT rules on tenant router
        RouterConfig rCfg = routerZkManager.get(fip.routerId);
        ruleZkManager.prepareCreateStaticSnatRule(ops, rCfg.outboundFilter,
            gwPort.id, fip.fixedIpv4Addr(), fip.floatingIpv4Addr());
        ruleZkManager.prepareCreateStaticDnatRule(ops, rCfg.inboundFilter,
            gwPort.id, fip.floatingIpv4Addr(), fip.fixedIpv4Addr());
    }

    private void prepareDisassociateFloatingIp(List<Op> ops, FloatingIp fip)
        throws SerializationException, StateAccessException {

        UUID prId = providerRouterZkManager.getId();

        // Remove all routes to this floating IP on provider router
        routeZkManager.prepareRoutesDelete(ops, prId, fip.floatingIpv4Subnet());

        // Go through router chains and remove all the NAT rules
        RouterConfig rCfg = routerZkManager.get(fip.routerId);
        ruleZkManager.prepareDeleteDnatRules(ops, rCfg.inboundFilter,
                                             fip.fixedIpv4Addr());
        ruleZkManager.prepareDeleteSnatRules(ops, rCfg.outboundFilter,
                                             fip.floatingIpv4Addr());

    }

    private void prepareUpdateNeutronFloatingIp(List<Op> ops, FloatingIp fip)
        throws SerializationException {
        String path = paths.getNeutronFloatingIpPath(fip.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(fip)));
    }

    public void prepareDisassociateFloatingIp(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        // TODO: Do something about this inefficiency
        FloatingIp fip = findFloatingIpByPort(port.id);
        if (fip == null) {
            LOGGER.warn("Floating IP was not found for port {}", port);
            return;
        }

        prepareDisassociateFloatingIp(ops, fip);
        fip.portId = null;
        prepareUpdateNeutronFloatingIp(ops, fip);
    }

    public void prepareUpdateFloatingIp(List<Op> ops, FloatingIp fip)
            throws org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException,
            SerializationException, StateAccessException {

        UUID prId = providerRouterZkManager.getId();
        FloatingIp oldFip = getFloatingIp(fip.id);

        // Disassociate if it's not associated with any fixed IP
        if (oldFip.isAssociated() && !fip.isAssociated()) {
            prepareDisassociateFloatingIp(ops, oldFip);
        } else if (!oldFip.isAssociated() && fip.isAssociated()) {
            // Associate fip to fixed
            prepareAssociateFloatingIp(ops, fip);
        } else if (oldFip.isAssociated()) {
            // Association modified.  No need to change the provider router
            // route, but need to update the static NAT rules.
            RouterConfig rCfg = routerZkManager.get(fip.routerId);
            RouterPortConfig gwPort =
                portZkManager.findFirstRouterPortByPeer(fip.routerId, prId);
            ruleZkManager.prepareReplaceSnatRules(ops, rCfg.outboundFilter,
                gwPort.id, fip.fixedIpv4Addr(), oldFip.floatingIpv4Addr(),
                fip.floatingIpv4Addr());
            ruleZkManager.prepareReplaceDnatRules(ops, rCfg.inboundFilter,
                gwPort.id, fip.floatingIpv4Addr(), oldFip.fixedIpv4Addr(),
                fip.fixedIpv4Addr());
        }

        prepareUpdateNeutronFloatingIp(ops, fip);
    }
}
