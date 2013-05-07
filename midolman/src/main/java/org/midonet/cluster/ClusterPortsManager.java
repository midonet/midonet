/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;

import org.midonet.cluster.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.state.LogicalPortConfig;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.util.functors.Callback1;

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

        Port<?> port = null;

        if (config instanceof PortDirectory.LogicalBridgePortConfig) {
            InteriorBridgePort interiorBridgePort =  new InteriorBridgePort();
            PortDirectory.LogicalBridgePortConfig cfg =
                (PortDirectory.LogicalBridgePortConfig) config;

            setPortFields(interiorBridgePort, cfg, id);
            setInternalPortFields(interiorBridgePort, cfg);

            port = interiorBridgePort;
        }
        else if (config instanceof PortDirectory.LogicalRouterPortConfig){
            InteriorRouterPort interiorRouterPort = new InteriorRouterPort();
            PortDirectory.LogicalRouterPortConfig cfg =
                (PortDirectory.LogicalRouterPortConfig) config;

            setPortFields(interiorRouterPort, cfg, id);
            setInternalPortFields(interiorRouterPort, cfg);
            setRouterPortFields(interiorRouterPort, cfg);

            port = interiorRouterPort;
        }
        else if (config instanceof PortDirectory.LogicalVlanBridgePortConfig) {
            InteriorVlanBridgePort ibvp =  new InteriorVlanBridgePort();
            PortDirectory.LogicalVlanBridgePortConfig cfg =
                (PortDirectory.LogicalVlanBridgePortConfig) config;

            setPortFields(ibvp, cfg, id);
            setInternalPortFields(ibvp, cfg);

            port = ibvp;
        }
        else if (config instanceof PortDirectory.MaterializedBridgePortConfig){
            ExteriorBridgePort exteriorBridgePort = new ExteriorBridgePort();
            PortDirectory.MaterializedBridgePortConfig cfg =
                (PortDirectory.MaterializedBridgePortConfig) config;

            setPortFields(exteriorBridgePort, cfg, id);
            setExteriorPortFieldsBridge(exteriorBridgePort, cfg);

            port = exteriorBridgePort;
        }
        else if (config instanceof PortDirectory.MaterializedRouterPortConfig){
            ExteriorRouterPort exteriorRouterPort = new ExteriorRouterPort();
            PortDirectory.MaterializedRouterPortConfig cfg =
                (PortDirectory.MaterializedRouterPortConfig) config;

            setPortFields(exteriorRouterPort, cfg, id);
            setExteriorPortFieldsRouter(exteriorRouterPort,cfg);
            setRouterPortFields(exteriorRouterPort, cfg);

            port = exteriorRouterPort;
        }
        else if (config instanceof PortDirectory.TrunkVlanBridgePortConfig){
            TrunkPort evbp = new TrunkPort();
            PortDirectory.TrunkVlanBridgePortConfig cfg =
                (PortDirectory.TrunkVlanBridgePortConfig) config;

            setPortFields(evbp, cfg, id);
            setTrunkPortFieldsVlanBridge(evbp, cfg);

            port = evbp;
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

    void setInternalPortFields(InteriorPort port, LogicalPortConfig cfg){
        port.setPeerID(cfg.peerId());
    }

    void setExteriorPortFieldsRouter(
            ExteriorPort port, PortDirectory.MaterializedRouterPortConfig cfg){

        port.setHostID(cfg.getHostId());
        port.setInterfaceName(cfg.getInterfaceName());
        if (cfg.portGroupIDs != null) {
            port.setPortGroups(cfg.portGroupIDs);
        } else {
        }
        port.setTunnelKey(cfg.tunnelKey);
    }

    void setExteriorPortFieldsBridge(
            ExteriorPort port, PortDirectory.MaterializedBridgePortConfig cfg){

        port.setHostID(cfg.getHostId());
        port.setInterfaceName(cfg.getInterfaceName());
        if (cfg.portGroupIDs != null) {
            port.setPortGroups(cfg.portGroupIDs);
        }
        port.setTunnelKey(cfg.tunnelKey);
    }

    void setTrunkPortFieldsVlanBridge(
        TrunkPort port, PortDirectory.TrunkVlanBridgePortConfig cfg){

        port.setHostID(cfg.getHostId());
        port.setInterfaceName(cfg.getInterfaceName());
        port.setTunnelKey(cfg.tunnelKey);
    }

    void setPortFields(Port port, PortConfig cfg, UUID id){
        port.setDeviceID(cfg.device_id);
        port.setInFilter(cfg.inboundFilter);
        port.setOutFilter(cfg.outboundFilter);
        port.setProperties(cfg.properties);
        port.setID(id);
    }

    void setRouterPortFields(RouterPort port,
                             PortDirectory.RouterPortConfig cfg){
        port.setPortAddr(new IPv4Subnet(
                IPv4Addr.fromString(cfg.getPortAddr()), cfg.nwLength));
        port.setPortMac(cfg.getHwAddr());
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
