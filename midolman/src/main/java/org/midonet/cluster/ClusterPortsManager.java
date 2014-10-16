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

import org.midonet.cluster.client.BridgePort;
import org.midonet.cluster.client.Port;
import org.midonet.cluster.client.PortBuilder;
import org.midonet.cluster.client.RouterPort;
import org.midonet.cluster.client.VxLanPort;
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

        Port port;

        if (config instanceof PortDirectory.BridgePortConfig) {
            port = new BridgePort();
        } else if (config instanceof PortDirectory.RouterPortConfig) {
            PortDirectory.RouterPortConfig cfg =
                (PortDirectory.RouterPortConfig) config;
            port = new RouterPort()
                .setPortAddr(new IPv4Subnet(
                    IPv4Addr.fromString(cfg.getPortAddr()), cfg.nwLength))
                .setPortMac(cfg.getHwAddr());
        } else if (config instanceof PortDirectory.VxLanPortConfig) {
            PortDirectory.VxLanPortConfig cfg =
                (PortDirectory.VxLanPortConfig) config;
            final IPv4Addr vtepAddr = IPv4Addr.fromString(cfg.mgmtIpAddr);
            final IPv4Addr vtepTunAddr = IPv4Addr.fromString(cfg.tunIpAddr);
            final UUID tzId = cfg.tunnelZoneId;
            final int vni = cfg.vni;
            port = new VxLanPort() {
                public IPv4Addr vtepAddr() { return vtepAddr; }
                public IPv4Addr vtepTunAddr() { return vtepTunAddr; }
                public UUID tunnelZoneId() { return tzId; }
                public int vni() { return vni; }
            };
        } else {
            throw new IllegalArgumentException("unknown Port type");
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
