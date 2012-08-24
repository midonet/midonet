/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.ChainBuilder;
import com.midokura.midonet.cluster.client.LocalStateBuilder;
import com.midokura.midonet.cluster.client.PortBuilders;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.util.functors.Callback1;

/**
 * Implementation of the Cluster.Client using ZooKeeper
 * Assumption:
 * - No cache, the caller of this class will have to implement its own cache
 * - Only one builder for UUID is allowed
 * - Right now it's single-threaded, we don't assure a thread-safe behaviour
 */
public class ZkClient implements Client {
    static final Logger log = LoggerFactory.getLogger(ZkClient.class);

    @Inject
    BridgeZkManager bridgeMgr;

    Map<UUID, BridgeBuilder> bridgeBuilderMap = new ConcurrentHashMap<UUID, BridgeBuilder>();
    Map<UUID, BridgeZkManager.BridgeConfig> bridgeMap = new ConcurrentHashMap<UUID, BridgeZkManager.BridgeConfig>();


    ExecutorService executorService = Executors.newFixedThreadPool(1);
    // TODO(ross): it is ok? do we really have one op for uuid?
    Map<UUID, Integer> pendingOperation = new HashMap<UUID, Integer>();

    @Override
    public void getBridge(UUID bridgeID, BridgeBuilder builder) {
        // asynchronous call, we will process it later
        bridgeBuilderMap.put(bridgeID, builder);
        executorService.submit(getBridgeConf(bridgeID, builder));
    }

    @Override
    public void getRouter(UUID routerID, RouterBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getChain(UUID chainID, ChainBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getType(UUID portID, Callback1<PortType> cb) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.InteriorBridgePortBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.ExteriorBridgePortBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.InteriorRouterPortBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.ExteriorRouterPortBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void getLocalStateFor(UUID hostIdentifier,
                                 LocalStateBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    interface ClusterTask extends Runnable {
        public UUID getId();
    }

    public ClusterTask getBridgeConf(final UUID id,
                                     final BridgeBuilder builder) {
        return new ClusterTask() {

            public UUID getId() {
                return id;
            }

            @Override
            public void run() {

                if(!pendingOperation.containsKey(id)){
                BridgeZkManager.BridgeConfig config = null;
                    try {
                        config = bridgeMgr.get(id, watchBridge(id));
                    } catch (StateAccessException e) {
                        // TODO send error message?
                    }
                    if (config != null) {
                        bridgeMap.put(id, config);
                        buildBridgeFromConfig(id, config, builder);
                    }
                }
        }
        };

    }


    public Runnable watchBridge(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                executorService.submit(getBridgeConf(id,
                                                     (BridgeBuilder) bridgeBuilderMap.get(id)));
            }
        };
    }

    public void stop() {
        executorService.shutdown();
    }
      /*
    void addBridgeBuilderForNotification(UUID deviceId, BridgeBuilder builder) {
        if (!bridgeBuilderMap.containsKey(deviceId)) {
            bridgeBuilderMap.put(deviceId, new LinkedList<BridgeBuilder>());
        }
        bridgeBuilderMap.get(deviceId).add(builder);
    }  */

    void buildBridgeFromConfig(UUID id, BridgeZkManager.BridgeConfig config,
                               BridgeBuilder builder) {

        builder.setID(id)
               .setInFilter(config.inboundFilter)
               .setOutFilter(config.outboundFilter);
        builder.setTunnelKey(config.greKey);
        // TODO(ross) check what's missing, MacLearningTable?
        builder.build();

    }
}
