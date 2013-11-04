/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cluster;

import org.midonet.cluster.client.BridgePort;
import org.midonet.cluster.client.Port;
import org.midonet.cluster.client.PortBuilder;
import org.midonet.cluster.client.RouterPort;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.util.functors.Callback1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClusterPortsManager extends ClusterManager<PortBuilder> {

    PortConfigCache portConfigCache;
    // These watchers belong to classes of the cluster (eg ClusterBridgeManager)
    // they don't implement the PortBuilder interface
    Map<UUID, Runnable> clusterWatchers = new HashMap<UUID, Runnable>();

    private static final Logger log = LoggerFactory
        .getLogger(ClusterPortsManager.class);

    @Inject
    public ClusterPortsManager(PortConfigCache configCache) {
        portConfigCache = configCache;
        configCache.addWatcher(getPortsWatcher());
    }

    protected <T extends PortConfig> T getPortConfigAndRegisterWatcher(
            final UUID id, Class<T> clazz, Runnable watcher) {
        T config = portConfigCache.get(id, clazz);
        clusterWatchers.put(id, watcher);
        return config;
    }

    @Override
    protected void getConfig(final UUID id) {
        PortConfig config = portConfigCache.get(id);
        if (config == null)
            return;

        Port port;

        if (config instanceof PortDirectory.BridgePortConfig) {
            port = new BridgePort();
        } else {
            PortDirectory.RouterPortConfig cfg =
                    (PortDirectory.RouterPortConfig) config;
            port = new RouterPort()
                .setPortAddr(new IPv4Subnet(
                    IPv4Addr.fromString(cfg.getPortAddr()), cfg.nwLength))
                .setPortMac(cfg.getHwAddr());
        }

        port.setTunnelKey(config.tunnelKey);
        port.setAdminStateUp(config.adminStateUp);
        port.setDeviceID(config.device_id);
        port.setInFilter(config.inboundFilter);
        port.setOutFilter(config.outboundFilter);
        port.setProperties(config.properties);
        port.setID(id);
        port.setPeerID(config.getPeerId());
        port.setHostID(config.getHostId());
        port.setInterfaceName(config.getInterfaceName());
        if (config.portGroupIDs != null) {
            port.setPortGroups(config.portGroupIDs);
        }

        PortBuilder builder = getBuilder(id);
        if (builder != null) {
            builder.setPort(port);
            log.debug("Build port {}, id {}", port, id);
            builder.build();
        }
        // this runnable notifies the classes in the cluster of a change in the
        // port configuration
        Runnable watcher = clusterWatchers.get(id);
        if (null != watcher)
            watcher.run();
    }

    public Callback1<UUID> getPortsWatcher(){
        return new Callback1<UUID>() {
            @Override
            public void call(UUID portId) {
               // this will be executed by the watcher in PortConfigCache
               // that is triggered by ZkDirectory, that has the same reactor as
               // the cluster client.
               getConfig(portId);
            }
        };
    }
}
