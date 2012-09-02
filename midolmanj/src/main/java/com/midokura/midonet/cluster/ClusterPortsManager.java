/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.util.UUID;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.LogicalPortConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midonet.cluster.client.ExteriorBridgePort;
import com.midokura.midonet.cluster.client.ExteriorPort;
import com.midokura.midonet.cluster.client.ExteriorRouterPort;
import com.midokura.midonet.cluster.client.InteriorBridgePort;
import com.midokura.midonet.cluster.client.InteriorPort;
import com.midokura.midonet.cluster.client.InteriorRouterPort;
import com.midokura.midonet.cluster.client.Port;
import com.midokura.midonet.cluster.client.PortBuilder;
import com.midokura.midonet.cluster.client.RouterPort;
import com.midokura.packets.IntIPv4;
import com.midokura.util.functors.Callback1;

public class ClusterPortsManager extends ClusterManager<PortBuilder> {

    @Inject
    PortConfigCache portConfigCache;

    private static final Logger log = LoggerFactory
        .getLogger(ClusterPortsManager.class);

    @Override
    public Runnable getConfig(final UUID id) {
        return new Runnable() {

            @Override
            public void run() {
                PortConfig config = portConfigCache.get(id);
                Port port = null;
                if (config instanceof PortDirectory.LogicalBridgePortConfig) {

                    port =  new InteriorBridgePort();
                    PortDirectory.LogicalBridgePortConfig cfg =
                        (PortDirectory.LogicalBridgePortConfig) config;
                    setPortFields(port, cfg, id);

                    setInternalPortFields((InteriorBridgePort)port, cfg);

                }
                else if (config instanceof PortDirectory.LogicalRouterPortConfig){
                    port = new InteriorRouterPort();
                    PortDirectory.LogicalRouterPortConfig cfg =
                        (PortDirectory.LogicalRouterPortConfig) config;
                    setPortFields(port, cfg, id);
                    setInternalPortFields((InteriorRouterPort)port,cfg);
                    setRouterPortFields((InteriorRouterPort)port,cfg);
                }
                else if (config instanceof PortDirectory.MaterializedBridgePortConfig){
                    port = new ExteriorBridgePort();
                    PortDirectory.MaterializedBridgePortConfig cfg =
                        (PortDirectory.MaterializedBridgePortConfig) config;
                    setPortFields(port, cfg, id);
                    setExteriorPortFieldsBridge((ExteriorBridgePort)port, cfg);
                }
                else if (config instanceof PortDirectory.MaterializedRouterPortConfig){
                    port = new ExteriorRouterPort();
                    PortDirectory.MaterializedRouterPortConfig cfg =
                        (PortDirectory.MaterializedRouterPortConfig) config;
                    setPortFields(port, cfg, id);
                    setExteriorPortFieldsRouter((ExteriorRouterPort)port,cfg);
                    setRouterPortFields((ExteriorRouterPort)port, cfg);
                }

                PortBuilder builder = getBuilder(id);
                builder.setPort(port);
                builder.build();
            }
        };
    }

    void setInternalPortFields(InteriorPort port, LogicalPortConfig cfg){
        port.setPeerID(cfg.peerId());
    }

    void setExteriorPortFieldsRouter(ExteriorPort port, PortDirectory.MaterializedRouterPortConfig cfg){
        
        port.setHostID(cfg.getHostId());
        port.setInterfaceName(cfg.getInterfaceName());
        port.setPortGroups(cfg.portGroupIDs);
        port.setTunnelKey(cfg.greKey);
    }

    void setExteriorPortFieldsBridge(ExteriorPort port, PortDirectory.MaterializedBridgePortConfig cfg){

        port.setHostID(cfg.getHostId());
        port.setInterfaceName(cfg.getInterfaceName());
        port.setPortGroups(cfg.portGroupIDs);
        port.setTunnelKey(cfg.greKey);
    }
    
    void setPortFields(Port port, PortConfig cfg, UUID id){
        port.setDeviceID(cfg.device_id);
        port.setInFilter(cfg.inboundFilter);
        port.setOutFilter(cfg.outboundFilter);
        port.setProperties(cfg.properties);
        port.setID(id);
    }
    
    void setRouterPortFields(RouterPort port, PortDirectory.RouterPortConfig cfg){
        port.setPortAddr(IntIPv4.fromString(cfg.getPortAddr()));
        port.setPortMac(cfg.getHwAddr());
    }

    public Callback1<UUID> getPortsWatcher(){
        return new Callback1<UUID>() {
            @Override
            public void call(UUID portId) {
               // this will be executed by the watcher in PortConfigCache
               // that is triggered by ZkDirectory, that has the same reactor as
               // the cluster client.
               getConfig(portId).run();
            }
        };
    }
}
