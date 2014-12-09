/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.PortBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.topology.devices.BridgePort;
import org.midonet.midolman.topology.devices.Port;
import org.midonet.midolman.topology.devices.RouterPort;
import org.midonet.midolman.topology.devices.VxLanPort;
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
    PortZkManager portMgr;

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
    protected void onNewBuilder(final UUID id) {
        PortBuilder builder = getBuilder(id);
        if (builder != null) {
            builder.setActive(isActive(id, builder));
            log.debug("Build port {}", id);
            builder.build();
        }
    }

    @Override
    protected void getConfig(final UUID id) {
        PortConfig config = portConfigCache.get(id);
        if (config == null)
            return;

        Port port;

        if (config instanceof PortDirectory.BridgePortConfig) {
            BridgePort p = new BridgePort();
            p.networkId_$eq(config.device_id);
            port = p;
        } else if (config instanceof PortDirectory.RouterPortConfig) {
            PortDirectory.RouterPortConfig cfg =
                (PortDirectory.RouterPortConfig) config;
            RouterPort p = new RouterPort();
            p.routerId_$eq(config.device_id);
            p.portIp_$eq(IPv4Addr.fromString(cfg.getPortAddr()));
            p.portSubnet_$eq(new IPv4Subnet(cfg.nwAddr, cfg.nwLength));
            p.portMac_$eq(cfg.getHwAddr());
            port = p;
        } else if (config instanceof PortDirectory.VxLanPortConfig) {
            PortDirectory.VxLanPortConfig cfg =
                (PortDirectory.VxLanPortConfig) config;
            final IPv4Addr vtepAddr = IPv4Addr.fromString(cfg.mgmtIpAddr);
            final IPv4Addr vtepTunAddr = IPv4Addr.fromString(cfg.tunIpAddr);
            final UUID tzId = cfg.tunnelZoneId;
            final int vni = cfg.vni;
            VxLanPort p = new VxLanPort();
            p.vxlanMgmtIp_$eq(vtepAddr);
            p.vxlanTunnelIp_$eq(vtepTunAddr);
            p.vxlanTunnelZoneId_$eq(tzId);
            p.vxlanVni_$eq(vni);
            port = p;
        } else {
            throw new IllegalArgumentException("unknown Port type");
        }


        port.tunnelKey_$eq(config.tunnelKey);
        port.adminStateUp_$eq(config.adminStateUp);
        port.inboundFilter_$eq(config.inboundFilter);
        port.outboundFilter_$eq(config.outboundFilter);
        port.id_$eq(id);
        port.peerId_$eq(config.getPeerId());
        port.hostId_$eq(config.getHostId());
        port.interfaceName_$eq(config.getInterfaceName());
        if (config.portGroupIDs != null) {
            scala.collection.immutable.Set<UUID> set = scala.collection
                .JavaConverters
                .asScalaSetConverter(config.portGroupIDs)
                .asScala().toSet();
            port.portGroups_$eq(set);
        }
        port.afterFromProto();

        PortBuilder builder = getBuilder(id);
        port.active_$eq(isActive(id, builder));
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

    private boolean isActive(final UUID portId, final Runnable watcher) {
        try {
            return portMgr.isActivePort(portId, watcher);
        } catch (StateAccessException e) {
            log.warn("Exception retrieving Port liveness", e);
            connectionWatcher.handleError(
                    "Port Liveness:" + portId, watcher, e);
        }
        return false;
    }

    private boolean isActive(final UUID portId, final PortBuilder builder) {
        return isActive(portId, aliveWatcher(portId, builder));
    }

    public Runnable aliveWatcher(final UUID portId, final PortBuilder builder) {
        return new Runnable() {
            @Override
            public void run() {
                log.debug("Port liveness changed: {}", portId);
                builder.setActive(isActive(portId, this));
                builder.build();
            }
        };
    }

    public Callback1<UUID> getPortsWatcher(){
        return new Callback1<UUID>() {
            @Override
            public void call(UUID portId) {
               // this will be executed by the watcher in PortConfigCache
               // that is triggered by ZkDirectory, that has the same reactor as
               // the cluster client.
                log.debug("Port changed: {}", portId);
               getConfig(portId);
            }
        };
    }
}
