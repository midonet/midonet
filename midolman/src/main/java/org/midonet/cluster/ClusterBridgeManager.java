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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import scala.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID;

public class ClusterBridgeManager extends ClusterManager<BridgeBuilder>{

    private static final Logger log = LoggerFactory
        .getLogger(ClusterBridgeManager.class);

    @Inject
    BridgeZkManager bridgeMgr;

    @Inject
    PortZkManager portMgr;

    @Inject
    ClusterPortsManager portsMgr;

    @Inject
    BridgeBuilderStateFeeder stateFeeder;

    @Override
    protected void getConfig(UUID id) {
        getBridgeConf(id, false);
    }

    void getBridgeConf(final UUID id, final boolean isUpdate) {
        log.info("Updating configuration for bridge {}", id);
        BridgeBuilder builder = getBuilder(id);
        if (builder == null) {
            log.error("Null builder for bridge {}", id.toString());
            return;
        }

        BridgeZkManager.BridgeConfig config = null;
        try {
            // we don't need to get the macPortMap again if it's an
            // update nor to create the logical port table.
            // For detecting changes to these maps we did set watchers.
            if (!isUpdate) {
                stateFeeder.feedLearningTable(builder, id, UNTAGGED_VLAN_ID);
                stateFeeder.feedIpToMacMap(builder, id);
                updateLogicalPorts(builder, id, false);
                updateExteriorPorts(builder, id);
            }

            /* NOTE(guillermo) this the last zk-related call in this block
             * so that the watcher is not added in an undefined state.
             * We would not want to add the watcher (with update=true) and
             * then find that the ZK calls for the rest of the data fail. */
            config = bridgeMgr.get(id, watchBridge(id, true));
        } catch (NoStatePathException e) {
            log.debug("Bridge {} has been deleted", id);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve the configuration for bridge {} - {}", id, e);
            connectionWatcher.handleError(
                id.toString(), watchBridge(id, isUpdate), e);
            return;
        } catch (SerializationException e) {
            log.error("Could not deserialize bridge config: {} - {}", id, e);
            return;
        }

        if (config == null) {
            log.warn("Received null bridge config for {}", id);
            return;
        }

        log.debug("Populating builder for bridge {}", id);
        builder.setAdminStateUp(config.adminStateUp);
        builder.setInFilter(config.inboundFilter)
               .setOutFilter(config.outboundFilter);
        builder.setTunnelKey(config.tunnelKey);
        builder.setExteriorVxlanPortIds(config.vxLanPortIds);
        builder.build();
        log.info("Added watcher for bridge {}", id);
    }

    Runnable watchBridge(final UUID id, final boolean isUpdate) {
        return new Runnable() {
            @Override
            public void run() {
                getBridgeConf(id, isUpdate); // return fast, update later
            }
        };
    }

    private void updateLogicalPorts(
            BridgeBuilder builder, UUID bridgeId, boolean isUpdate)
            throws StateAccessException {

        // This implementation won't keep the old tables and compute a diff
        // on the contrary it will create new tables every time
        // and pass them to the builder
        Map<MAC, UUID> rtrMacToLogicalPortId = new HashMap<>();
        Map<IPAddr, MAC> rtrIpToMac =  new HashMap<>();
        VlanPortMapImpl vlanIdPortMap = new VlanPortMapImpl();
        UUID vlanBridgePeerPortId = null;
        Collection<UUID> logicalPortIDs;
        Set<Short> currentVlans = new HashSet<>();
        currentVlans.add(UNTAGGED_VLAN_ID);

        LogicalPortWatcher watcher = new LogicalPortWatcher(bridgeId, builder);
        try {
            logicalPortIDs = portMgr.getBridgeLogicalPortIDs(bridgeId, watcher);
        } catch (StateAccessException e) {
            log.error("Failed to retrieve the logical port IDs for bridge {}",
                      bridgeId);
            throw e;
        }

        for (UUID id : logicalPortIDs) {
            log.debug("Found logical port {} for bridge {}", id, bridgeId);
            // TODO(rossella) consider keeping in memory the old ports list
            // so that the watcher can consider just the port whose
            // configuration changed, not the whole list.

            PortDirectory.BridgePortConfig bridgePort = portsMgr
                .getPortConfigAndRegisterWatcher(
                    id, PortDirectory.BridgePortConfig.class, watcher);

            if (null == bridgePort) {
                log.warn("Can't find the logical bridge port's config {}", id);
                continue;
            }

            if (null == bridgePort.getPeerId()) {
                log.error("There shouldn't be a dangling " +
                          "port in the 'logical-ports/' subfolder.");
                continue;
            }

            PortConfig peerPortCfg = portsMgr.getPortConfigAndRegisterWatcher(
                bridgePort.getPeerId(),
                PortConfig.class,
                watcher);

            if (peerPortCfg instanceof PortDirectory.RouterPortConfig) {
                log.debug("Bridge peer is a Router's interior port");
                PortDirectory.RouterPortConfig routerPort =
                    (PortDirectory.RouterPortConfig)peerPortCfg;
                // 'Learn' that the router's mac is reachable via the bridge port.
                rtrMacToLogicalPortId.put(routerPort.getHwAddr(), id);
                // Add the router port's IP and MAC to the permanent ARP map.
                IPv4Addr rtrPortIp = IPv4Addr.fromInt(routerPort.portAddr);
                rtrIpToMac.put(rtrPortIp, routerPort.getHwAddr());
                log.debug("Add bridge port {} linked to router port, MAC:{}, IP:{}",
                          id, routerPort.getHwAddr(), rtrPortIp);
            } else if (peerPortCfg instanceof PortDirectory.BridgePortConfig) {
               log.debug("Bridge peer is another Bridge's interior port");
                // Let's see who of the two is acting as vlan-aware bridge
                Short bridgePortVlanId = bridgePort.getVlanId();
                if (null == bridgePortVlanId) { // it's the peer
                    PortDirectory.BridgePortConfig typedPeerCfg =
                        ((PortDirectory.BridgePortConfig) peerPortCfg);
                    Short herVlanId = typedPeerCfg.getVlanId();
                    if (herVlanId == null) {
                        log.warn("Peer is vlan-aware, but has no vlan id {}",
                                 bridgePort.getPeerId());
                    } else {
                        log.debug("Bridge peer is vlan-aware, my vlan-id {}",
                                  herVlanId);
                        vlanBridgePeerPortId = id;
                    }
                } else { // it's the bridge
                    log.debug("Bridge peer {} mapped to vlan-id {}",
                              bridgePort.getPeerId(), bridgePortVlanId);
                    vlanIdPortMap.add(bridgePortVlanId, id);
                    currentVlans.add(bridgePortVlanId);
                }
            } else {
                log.warn("The peer isn't a router or vlan-bridge logical port");
            }
        }

        // Deal with VLAN changes
        Set<Short> oldVlans = builder.vlansInMacLearningTable();

        Set<Short> deletedVlans = new HashSet<>(oldVlans);
        deletedVlans.removeAll(currentVlans);

        Set<Short> createdVlans = new HashSet<>(currentVlans);
        createdVlans.removeAll(oldVlans);

        for(Short newVlan: createdVlans) {
            try {
                // Create a MAC learning table for VLAN we hadn't seen before.
                stateFeeder.feedLearningTable(builder, bridgeId, newVlan);
            } catch (StateAccessException e) {
                log.warn("Error retrieving mac-ports for VLAN ID" +
                        " {}, bridge {}", newVlan, bridgeId, e);
                Runnable retrier = watchBridge(bridgeId, isUpdate);
                connectionWatcher.handleError(bridgeId.toString(), retrier, e);
            }
        }

        for(Short deletedVlanId: deletedVlans) {
            builder.removeMacLearningTable(deletedVlanId);
        }

        builder.setVlanBridgePeerPortId(Option.apply(vlanBridgePeerPortId));
        builder.setLogicalPortsMap(rtrMacToLogicalPortId, rtrIpToMac);
        builder.setVlanPortMap(vlanIdPortMap);
    }

    void buildLogicalPortUpdates(BridgeBuilder builder, UUID bridgeId)
            throws StateAccessException {
        updateLogicalPorts(builder, bridgeId, true);
        builder.build();
    }

    class LogicalPortWatcher implements Runnable {
        UUID bridgeID;
        BridgeBuilder builder;

        LogicalPortWatcher(UUID bridgeID, BridgeBuilder builder) {
            this.bridgeID = bridgeID;
            this.builder = builder;
        }

        public String describe() {
            return "BridgeLogicalPorts:" + bridgeID;
        }

        public void run() {
            try {
                buildLogicalPortUpdates(builder, bridgeID);
            } catch (StateAccessException e) {
                connectionWatcher.handleError(describe(), this, e);
            }
        }
    }

    private void updateExteriorPorts(BridgeBuilder builder, UUID bridgeId)
            throws StateAccessException {
        Runnable watcher = new BridgePortWatcher(bridgeId, builder);
        updateExteriorPorts(builder, bridgeId, watcher);
    }

    private void updateExteriorPorts(BridgeBuilder builder, UUID bridgeId, Runnable watcher)
            throws StateAccessException {
        List<UUID> ports = portMgr.getBridgePortIDs(bridgeId, watcher);
        builder.setExteriorPorts(ports);
    }

    class BridgePortWatcher implements Runnable {
        UUID bridgeId;
        BridgeBuilder builder;

        BridgePortWatcher(UUID bridgeId, BridgeBuilder builder) {
            this.bridgeId = bridgeId;
            this.builder = builder;
        }

        public String describe() {
            return "BridgePorts:" + bridgeId;
        }

        public void run() {
            try {
                updateExteriorPorts(builder, bridgeId, this);
                builder.build();
            } catch (StateAccessException e) {
                connectionWatcher.handleError(describe(), this, e);
            }
        }
    }

}
