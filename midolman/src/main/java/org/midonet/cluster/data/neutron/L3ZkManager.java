/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.zkManagers.*;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.packets.IPv4Subnet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class L3ZkManager extends BaseZkManager {

    private final NetworkZkManager networkZkManager;
    private final PortZkManager portZkManager;
    private final RouteZkManager routeZkManager;
    private final RouterZkManager routerZkManager;
    private final ChainZkManager chainZkManager;

    @Inject
    public L3ZkManager(ZkManager zk,
                       PathBuilder paths,
                       Serializer serializer,
                       NetworkZkManager networkZkManager,
                       ChainZkManager chainZkManager,
                       PortZkManager portZkManager,
                       RouteZkManager routeZkManager,
                       RouterZkManager routerZkManager) {
        super(zk, paths, serializer);
        this.networkZkManager = networkZkManager;
        this.chainZkManager = chainZkManager;
        this.portZkManager = portZkManager;
        this.routeZkManager = routeZkManager;
        this.routerZkManager = routerZkManager;
    }

    public Router getRouter(UUID routerId)
            throws StateAccessException, SerializationException {

        String path = paths.getNeutronRouterPath(routerId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), Router.class);
    }

    public List<Router> getRouters()
            throws StateAccessException, SerializationException {

        String path= paths.getNeutronRoutersPath();
        Set<UUID> routerIds = getUuidSet(path);

        List<Router> routers = new ArrayList<>();
        for (UUID routerId : routerIds) {
            routers.add(getRouter(routerId));
        }

        return routers;
    }

    public void prepareCreateRouter(List<Op> ops, Router router)
            throws SerializationException, StateAccessException {

        UUID preChainId = UUID.randomUUID();
        UUID postChainId = UUID.randomUUID();

        // Create chains with no rule.  These chains will be used for
        // floating IP static NAT
        ops.addAll(chainZkManager.prepareCreate(preChainId,
                new ChainConfig(router.preRouteChainName())));
        ops.addAll(chainZkManager.prepareCreate(postChainId,
                new ChainConfig(router.postRouteChainName())));

        RouterConfig config = new RouterConfig(router, preChainId, postChainId);
        ops.addAll(routerZkManager.prepareRouterCreate(router.id, config));

        // The path to 'ref' directory gets created twice, once in
        // prepareChainCreate and then again in prepareRouterCreate.
        // Remove the last occurrence.
        zk.removeLastOp(ops, paths.getChainBackRefsPath(postChainId));
        zk.removeLastOp(ops, paths.getChainBackRefsPath(preChainId));

        String path = paths.getNeutronRouterPath(router.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(router)));
    }

    public void prepareDeleteRouter(List<Op> ops, UUID id)
            throws SerializationException, StateAccessException {

        RouterConfig config = routerZkManager.get(id);
        if (config == null) return;

        ops.addAll(chainZkManager.prepareDelete(config.inboundFilter));
        ops.addAll(chainZkManager.prepareDelete(config.outboundFilter));

        ops.addAll(routerZkManager.prepareRouterDelete(id));

        // The path to 'ref' directory gets deleted twice, once in
        // prepareChainDelete and then again in prepareRouterDelete.
        // Remove the last occurrence.
        String inRefPaths = paths.getChainBackRefPath(config.inboundFilter,
                ResourceType.ROUTER.toString(), id);
        String outRefPaths = paths.getChainBackRefPath(config.outboundFilter,
                ResourceType.ROUTER.toString(), id);
        zk.removeLastOp(ops, inRefPaths);
        zk.removeLastOp(ops, outRefPaths);

        ops.add(zk.getDeleteOp(paths.getNeutronRouterPath(id)));
    }

    public void prepareUpdateRouter(List<Op> ops, Router newRouter)
            throws SerializationException, StateAccessException {

        RouterConfig config = routerZkManager.get(newRouter.id);
        config.name = newRouter.name;
        List<Op> updateOps = routerZkManager.prepareUpdate(newRouter.id,
                config);
        if (updateOps != null) {
            ops.addAll(updateOps);
        }

        // Update the neutron router config
        ops.add(zk.getSetDataOp(paths.getNeutronRouterPath(newRouter.id),
                serializer.serialize(newRouter)));
    }

    public void prepareCreateRouterInterfacePort(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        // Create a port on the bridge but leave it unlinked.  When
        // prepareCreateRouterInterface is executed, this port is linked.
        BridgePortConfig bpConfig = new BridgePortConfig(port.networkId, true);
        ops.addAll(portZkManager.prepareCreate(port.id, bpConfig));
    }

    public void prepareCreateRouterInterface(List<Op> ops,
                                             RouterInterface rInt)
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
        }

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
                new IPv4Subnet(0, 0), subnet.ipv4Subnet(), rpId, null, 100,
                rInt.id, rpConfig);

        // Add a route for the metadata server.
        // Not all VM images supports DHCP option 121.  Add a route for the
        // Metadata server in the router to forward the packet to the bridge
        // that will send them to the Metadata Proxy.
        Port dPort = networkZkManager.getDhcpPort(subnet.networkId);
        if (dPort != null && dPort.hasIp()) {

            routeZkManager.preparePersistPortRouteCreate(ops,
                    UUID.randomUUID(), new IPv4Subnet(0, 0),
                    MetaDataService.IPv4_SUBNET, rpId, dPort.firstIpv4Addr(),
                    100, rInt.id, rpConfig);
        }
    }

    public void prepareDeleteRouterInterfacePort(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        PortConfig p = portZkManager.get(port.id);
        portZkManager.prepareDelete(ops, p, true);
    }
}
