/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import scala.Option;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.BridgeBuilder;
import org.midonet.cluster.client.IpMacMap;
import org.midonet.cluster.client.MacLearningTable;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.ReplicatedMap;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkPathManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback3;

public class ClusterBridgeManager extends ClusterManager<BridgeBuilder>{

    @Inject
    BridgeZkManager bridgeMgr;

    @Inject
    ZookeeperConfig zkConfig;

    @Inject
    Directory dir;

    @Inject
    PortZkManager portMgr;

    @Inject
    ClusterPortsManager portsMgr;

    private static final Logger log = LoggerFactory
        .getLogger(ClusterBridgeManager.class);

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
        Ip4ToMacReplicatedMap ip4MacMap = null;
        try {
            // we don't need to get the macPortMap again if it's an
            // update nor to create the logical port table.
            // For detecting changes to these maps we did set watchers.
            if (!isUpdate) {
                ZkPathManager pathManager = new ZkPathManager(
                        zkConfig.getMidolmanRootKey());
                setMacLearningTable(
                        pathManager, id, Bridge.UNTAGGED_VLAN_ID, builder);

                ip4MacMap = new Ip4ToMacReplicatedMap(
                    bridgeMgr.getIP4MacMapDirectory(id));
                ip4MacMap.setConnectionWatcher(connectionWatcher);
                ip4MacMap.start();
                builder.setIp4MacMap(new IpMacMapImpl(id, ip4MacMap));
                updateLogicalPorts(builder, id, false);
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
        } catch (KeeperException e) {
            log.warn("Cannot retrieve the configuration for bridge {} - {}", id, e);
            connectionWatcher.handleError(
                    id.toString(), watchBridge(id, isUpdate), e);
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
        builder.setExteriorVxlanPortId(Option.apply(config.vxLanPortId));
        builder.build();
        log.info("Added watcher for bridge {}", id);
    }

    Runnable watchBridge(final UUID id, final boolean isUpdate) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                getBridgeConf(id, isUpdate);
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
        currentVlans.add(Bridge.UNTAGGED_VLAN_ID);

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

            // Ignore dangling ports.
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
                          new Object[]{id, routerPort.getHwAddr(), rtrPortIp});
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

        ZkPathManager pathManager = new ZkPathManager(
                zkConfig.getMidolmanRootKey());

        for(Short createdVlanId: createdVlans) {
            try {
                // Create a MAC learning table for VLAN we hadn't seen before.
                setMacLearningTable(
                        pathManager, bridgeId, createdVlanId, builder);
            } catch (KeeperException e) {
                log.warn("Error retrieving mac-ports for VLAN ID" +
                        " {}, bridge {}",
                        new Object[]{createdVlanId, bridgeId}, e);
                connectionWatcher.handleError(
                        bridgeId.toString(),
                        watchBridge(bridgeId, isUpdate), e);
            }
        }

        for(Short deletedVlanId: deletedVlans) {
            builder.removeMacLearningTable(deletedVlanId);
        }

        builder.setVlanBridgePeerPortId(Option.apply(vlanBridgePeerPortId));
        builder.setLogicalPortsMap(rtrMacToLogicalPortId, rtrIpToMac);
        builder.setVlanPortMap(vlanIdPortMap);
    }

    /**
     * Creates a MAC learning table for the bridge with the specified VLAN or
     * no VLAN ID, and sets it to the BridgeBuilder.
     *
     * @param pathManager ZkPathManager for the ZK MAC/ports table dir path.
     * @param bridgeId A bridge UUID.
     * @param vlanId A VLAN ID or UNTAGGED_VLAN_ID.
     * @param builder A BridgeBuilder instance.
     * @throws KeeperException If failed to create a MacPortMap for the bridge /
     * the VLAN ID.
     */
    void setMacLearningTable(ZkPathManager pathManager, UUID bridgeId, short vlanId,
                             BridgeBuilder builder) throws KeeperException {
        MacPortMap macPortMap = new MacPortMap(dir.getSubDirectory(
                pathManager.getBridgeMacPortsPath(bridgeId, vlanId)));
        macPortMap.setConnectionWatcher(connectionWatcher);
        macPortMap.start();
        MacLearningTable table = new MacLearningTableImpl(
                bridgeId, macPortMap, vlanId);
        builder.setMacLearningTable(vlanId, table);
        table.notify(new MacTableNotifyCallBack(vlanId, builder));
    }

    void buildLogicalPortUpdates(BridgeBuilder builder, UUID bridgeId)
            throws StateAccessException {
        updateLogicalPorts(builder, bridgeId, true);
        builder.build();
    }

    class MacLearningTableImpl implements MacLearningTable {

        final MacPortMap map;
        final UUID bridgeID;
        final short vlanId;

        MacLearningTableImpl(UUID bridgeID, MacPortMap map, short vlanId) {
            this.bridgeID = bridgeID;
            this.map = map;
            this.vlanId = vlanId;
        }

        /* It's ok to do a synchronous get on the map because it only queries
         * local state (doesn't go remote like the other calls.
         */
        @Override
        public UUID get(final MAC mac) {
            return map.get(mac);
        }

        @Override
        public void add(final MAC mac, final UUID portID) {
            reactorLoop.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        map.put(mac, portID);
                    } catch (Exception e) {
                        log.error("Failed adding mac {}, VLAN {} to port {}",
                                  new Object[]{mac, vlanId, portID, e});
                    }
                    log.info("Added mac {}, VLAN {} to port {} for bridge {}",
                             new Object[]{mac, vlanId, portID, bridgeID});
                }
            });
        }

        @Override
        public void remove(final MAC mac, final UUID portID) {
            reactorLoop.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        map.removeIfOwnerAndValue(mac, portID);
                    } catch (Exception e) {
                        log.error("Failed removing mac {}, VLAN {} from port {}",
                                  new Object[]{mac, vlanId, portID, e});
                    }
                }
            });
        }

        // This notify() registers its callback directly with the underlying
        // MacPortMap map, so the callbacks are called from MacPortMap context
        // and should perform ActorRef::tell or such to switch to the context
        // appropriate for the callback's work.
        @Override
        public void notify(final Callback3<MAC, UUID, UUID> cb) {
            reactorLoop.submit(new Runnable() {

                @Override
                public void run() {
                    map.addWatcher(new ReplicatedMap.Watcher<MAC, UUID>() {
                        @Override
                        public void processChange(MAC key, UUID oldValue,
                                                  UUID newValue) {
                            cb.call(key, oldValue, newValue);
                        }
                    });
                }
            });
        }
    }

    class IpMacMapImpl implements IpMacMap<IPv4Addr> {

        Ip4ToMacReplicatedMap map;
        UUID bridgeID;

        IpMacMapImpl(UUID bridgeID, Ip4ToMacReplicatedMap map) {
            this.bridgeID = bridgeID;
            this.map = map;
        }

        /* It's ok to do a synchronous get on the map because it only queries
         * local state (doesn't go remote like the other calls.
         */
        @Override
        public MAC get(final IPv4Addr ip) {
            return map.get(ip);
        }
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

    private class MacTableNotifyCallBack implements Callback3<MAC, UUID, UUID> {
        private short vlanId;
        private BridgeBuilder builder = null;
        public MacTableNotifyCallBack(short vlanId,
                                      BridgeBuilder builder) {
            this.vlanId = vlanId;
            this.builder = builder;
        }

        @Override
        public void call(MAC mac, UUID oldPort, UUID newPort) {
            builder.updateMacEntry(vlanId, mac, oldPort, newPort);
        }
    }

}
