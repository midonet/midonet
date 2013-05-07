/*
* Copyright 2012 Midokura Pte. Ltd.
*/

package org.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;

import org.midonet.cluster.client.VlanAwareBridgeBuilder;
import org.midonet.cluster.client.VlanPortMap;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.VlanAwareBridgeZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterVlanBridgeManager extends ClusterManager<VlanAwareBridgeBuilder>{

    @Inject
    VlanAwareBridgeZkManager bridgeZkMgr;

    @Inject
    PortZkManager portZkManager;

    @Inject
    ClusterPortsManager portsManager;

    private static final Logger log = LoggerFactory
        .getLogger(ClusterVlanBridgeManager.class);

    @Override
    protected void getConfig(UUID id) {
        getVlanBridgeConf(id, false);
    }

    void getVlanBridgeConf(final UUID id, final boolean isUpdate) {
        log.info("Updating configuration for vlan-bridge {}", id);
        VlanAwareBridgeBuilder builder = getBuilder(id);
        if(builder == null){
            log.error("Null builder for vlan-bridge {}", id.toString());
            return;
        }

        VlanAwareBridgeZkManager.VlanBridgeConfig config = null;
        Set<UUID> trunkPortIds = null;
        try {
            if (!isUpdate) {
                updateLogicalPorts(builder, id, false);
            }
            config = bridgeZkMgr.get(id, watchVlanBridge(id, true));
            trunkPortIds = portZkManager.getVlanBridgeTrunkPortIDs(id);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve config for vlan bridge {}", id, e);
            connectionWatcher.handleError(
                id.toString(), watchVlanBridge(id, isUpdate), e);
            return;
        }

        if (config == null) {
            log.warn("Received null vlan bridge config for {}", id);
            return;
        }

        log.debug("Populating builder for vlan bridge {}", id);
        builder.setTunnelKey(config.getTunnelKey());
        builder.setTrunks(trunkPortIds);
        builder.build();
        log.info("Added watcher for vlan bridge {}", id);
    }

    Runnable watchVlanBridge(final UUID id, final boolean isUpdate) {
        return new Runnable() {
            @Override public void run() { getVlanBridgeConf(id, isUpdate); }
        };
    }

    // Mostly copied from ClusterBridgeManager
    void updateLogicalPorts(VlanAwareBridgeBuilder builder, UUID bridgeId, boolean isUpdate)
        throws StateAccessException {

        VlanPortMapImpl vlanIdPortMap = new VlanPortMapImpl();
        Set<UUID> portIds = null;
        LogicalPortWatcher watcher = new LogicalPortWatcher(bridgeId, builder);
        try {
            portIds = portZkManager.getVlanBridgeLogicalPortIDs(bridgeId, watcher);
        } catch (StateAccessException e) {
            log.error("Failed to retrieve the logical port IDs for " +
                          "vlan-bridge {}", bridgeId);
            throw e;
        }

        for (UUID id: portIds) {
            log.debug("Found logical port {}" , id);
            PortDirectory.LogicalVlanBridgePortConfig port =
                portsManager.getPortConfigAndRegisterWatcher(id,
                      PortDirectory.LogicalVlanBridgePortConfig.class, watcher);
            if (null == port) {
                log.warn("Can't find logical vlan-bridge port's cfg {}", id);
                continue;
            } else if (null == port.peerId()) {
                log.debug("Ignore dangling port {}", id);
                continue;
            } else if (null == port.vlanId()) {
                log.debug("Ignore port without vlan id {}", id);
            }

            vlanIdPortMap.add(port.vlanId(), id);
            log.debug("Mapped vlan id {} to port {}", port.vlanId(), id);
        }
        builder.setVlanPortMap(vlanIdPortMap);

        if(isUpdate)
            builder.build();

    }

    class LogicalPortWatcher implements Runnable {
        UUID bridgeID;
        VlanAwareBridgeBuilder builder;

        LogicalPortWatcher(UUID bridgeID, VlanAwareBridgeBuilder builder) {
            this.bridgeID = bridgeID;
            this.builder = builder;
        }

        public String describe() {
            return "VlanAwareBridgeTrunkPorts:" + bridgeID;
        }

        public void run() {
            try {
                updateLogicalPorts(builder, bridgeID, true);
            } catch (StateAccessException e) {
                connectionWatcher.handleError(describe(), this, e);
            }
        }
    }

    class VlanPortMapImpl implements VlanPortMap {

        private Map<Short, UUID> dir = new HashMap<Short, UUID>();
        private Map<UUID, Short> rev = new HashMap<UUID, Short>();

        public void add(Short vlanId, UUID portId) {
            dir.put(vlanId, portId);
            rev.put(portId, vlanId);
        }

        @Override
        public Short getVlan(UUID portId) {
            return rev.get(portId);
        }

        @Override
        public UUID getPort(Short vlanId) {
            return dir.get(vlanId);
        }

        @Override
        public String toString() {
            return "Direct vlan->port map: {" + dir.toString() + "}";
        }
    }
}