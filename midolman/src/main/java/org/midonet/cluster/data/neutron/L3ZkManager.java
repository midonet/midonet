/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.midolman.state.zkManagers.*;

import java.util.*;

public class L3ZkManager extends BaseZkManager {

    private final RouterZkManager routerZkManager;
    private final ChainZkManager chainZkManager;

    @Inject
    public L3ZkManager(ZkManager zk,
                       PathBuilder paths,
                       Serializer serializer,
                       ChainZkManager chainZkManager,
                       RouterZkManager routerZkManager) {
        super(zk, paths, serializer);
        this.chainZkManager = chainZkManager;
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

    public RouterConfig prepareCreateRouter(List<Op> ops, Router router)
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

        return config;
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
}
