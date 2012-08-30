/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.ChainBuilder;
import com.midokura.midonet.cluster.client.LocalStateBuilder;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.util.eventloop.Reactor;

/**
 * Implementation of the Cluster.Client using ZooKeeper
 * Assumption:
 * - No cache, the caller of this class will have to implement its own cache
 * - Only one builder for UUID is allowed
 * - Right now it's single-threaded, we don't assure a thread-safe behaviour
 */
public class LocalClientImpl implements Client {

    private static final Logger log = LoggerFactory
        .getLogger(LocalClientImpl.class);

    @Inject
    HostZkManager hostZkManager;

    @Inject
    BgpZkManager bgpZkManager;

    @Inject
    ClusterRouterManager routerManager;

    @Inject
    ClusterBridgeManager bridgeManager;


    @Inject
    ClusterPortsManager portsManager;


    /**
     * We inject it because we want to use the same {@link Reactor} as {@link ZkDirectory}
     */
    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Override
    public void getBridge(UUID bridgeID, BridgeBuilder builder) {
        // asynchronous call, we will process it later
        try {
            bridgeManager.registerNewBuilder(bridgeID, builder);
        } catch (ClusterClientException e) {
            //TODO(ross) what should be sent back in case of error?
        }
        reactorLoop.submit(
            bridgeManager.getConfig(bridgeID));
        log.info("getBridge {}", bridgeID);
    }

    @Override
    public void getRouter(UUID routerID, RouterBuilder builder) {
        try {
            routerManager.registerNewBuilder(routerID, builder);
        } catch (ClusterClientException e) {
            //TODO(ross) what should be send back in case of error?
        }
        reactorLoop.submit(
            routerManager.getConfig(routerID));
        log.info("getRouter {}", routerID);
    }

    @Override
    public void getChain(UUID chainID, ChainBuilder builder) {
    }

    @Override
    public void getPort(UUID portID, PortBuilder builder) {

        try {
            portsManager.registerNewBuilder(portID, builder);
        } catch (ClusterClientException e) {
            //TODO(ross) what should be send back in case of error?
        }
        reactorLoop.submit(portsManager.getConfig(portID));
    }

    Map<UUID, LocalStateBuilder> localStateBuilders =
        new HashMap<UUID, LocalStateBuilder>();

    @Override
    public void getLocalStateFor(UUID hostIdentifier,
                                 LocalStateBuilder builder) {
        localStateBuilders.put(hostIdentifier, builder);
        triggerUpdate(hostIdentifier);
    }

    private void triggerUpdate(UUID hostIdentifier) {
        try {

            LocalStateBuilder builder = localStateBuilders.get(hostIdentifier);
            if (builder == null)
                return;

            builder.setDatapathName(
                hostZkManager.getVirtualDatapathMapping(hostIdentifier));

            Set<HostDirectory.VirtualPortMapping> portMappings =
                hostZkManager.getVirtualPortMappings(hostIdentifier);

            for (HostDirectory.VirtualPortMapping portMapping : portMappings) {
                builder.addLocalPortInterface(portMapping.getVirtualPortId(),
                                              portMapping.getLocalDeviceName());
            }

            builder.build();
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }


}
