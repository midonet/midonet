/*
 * @(#)MgmtRouterZkManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.util.ShortUUID;

public class RouterZkManagerProxy extends ZkMgmtManager {

    public static class RouterMgmtConfig {

        public RouterMgmtConfig() {
            super();
        }

        public RouterMgmtConfig(UUID tenantId, String name) {
            super();
            this.tenantId = tenantId;
            this.name = name;
        }

        public UUID tenantId;
        public String name;
    }

    public static class PeerRouterConfig {
        public PeerRouterConfig() {
            super();
        }

        public PeerRouterConfig(UUID portId, UUID peerPortId) {
            super();
            this.portId = portId;
            this.peerPortId = peerPortId;
        }

        public UUID portId;
        public UUID peerPortId;
    }

    private RouterZkManager zkManager = null;

    public RouterZkManagerProxy(ZooKeeper zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new RouterZkManager(zk, basePath);
    }

    public List<Op> prepareRouterCreate(
            ZkNodeEntry<UUID, RouterMgmtConfig> routerMgmtNode)
            throws ZkStateSerializationException, StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(
                    mgmtPathManager.getRouterPath(routerMgmtNode.key),
                    serialize(routerMgmtNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterMgmtConfig", e,
                    RouterMgmtConfig.class);
        }

        // Add a node to keep track of inter-router connections.
        ops.add(Op.create(mgmtPathManager
                .getRouterRoutersPath(routerMgmtNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add under tenant.
        ops.add(Op.create(mgmtPathManager.getTenantRouterPath(
                routerMgmtNode.value.tenantId, routerMgmtNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        ops.addAll(zkManager.prepareRouterCreate(routerMgmtNode.key));
        return ops;
    }

    public List<Op> preparePortCreateLink(
            ZkNodeEntry<UUID, PortDirectory.PortConfig> localPortEntry,
            ZkNodeEntry<UUID, PortDirectory.PortConfig> peerPortEntry)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(mgmtPathManager.getPortPath(localPortEntry.key),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(mgmtPathManager.getPortPath(peerPortEntry.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        PortZkManager portZkManager = new PortZkManager(zooKeeper, pathManager
                .getBasePath());
        ops.addAll(portZkManager.preparePortCreateLink(localPortEntry,
                peerPortEntry));

        PeerRouterConfig peerRouter = new PeerRouterConfig(localPortEntry.key,
                peerPortEntry.key);
        PeerRouterConfig localRouter = new PeerRouterConfig(peerPortEntry.key,
                localPortEntry.key);
        try {
            ops.add(Op.create(mgmtPathManager.getRouterRouterPath(
                    localPortEntry.value.device_id,
                    peerPortEntry.value.device_id), serialize(peerRouter),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            ops.add(Op.create(mgmtPathManager.getRouterRouterPath(
                    peerPortEntry.value.device_id,
                    localPortEntry.value.device_id), serialize(localRouter),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize peer routers to PeerRouterConfig",
                    e, PeerRouterConfig.class);
        }

        return ops;
    }

    public List<Op> prepareRouterDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareRouterDelete(get(id));
    }

    public List<Op> prepareRouterDelete(
            ZkNodeEntry<UUID, RouterMgmtConfig> routerMgmtNode)
            throws StateAccessException, ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();
        ops.addAll(zkManager.prepareRouterDelete(routerMgmtNode.key));

        // Delete the tenant router entry
        ops.add(Op.delete(mgmtPathManager.getTenantRouterPath(
                routerMgmtNode.value.tenantId, routerMgmtNode.key), -1));

        // Remove all the ports in mgmt directory but don't cascade here.
        PortZkManagerProxy portMgr = new PortZkManagerProxy(zooKeeper,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        PortZkManager portZkManager = new PortZkManager(zk, pathManager
                .getBasePath());
        List<ZkNodeEntry<UUID, PortConfig>> portNodes = portZkManager
                .listRouterPorts(routerMgmtNode.key);
        for (ZkNodeEntry<UUID, PortConfig> portNode : portNodes) {
            ops.addAll(portMgr.preparePortDelete(portNode.key, false));
            // TODO: Remove VIF if plugged, and remove peer port for logical
        }

        // Remove the router-router mappings
        Set<String> peers = getChildren(mgmtPathManager
                .getRouterRoutersPath(routerMgmtNode.key), null);
        for (String peer : peers) {
            UUID peerId = UUID.fromString(peer);
            ops.add(Op.delete(mgmtPathManager.getRouterRouterPath(
                    routerMgmtNode.key, peerId), -1));
            ops.add(Op.delete(mgmtPathManager.getRouterRouterPath(peerId,
                    routerMgmtNode.key), -1));
        }
        ops.add(Op.delete(mgmtPathManager
                .getRouterRoutersPath(routerMgmtNode.key), -1));

        ops.add(Op
                .delete(mgmtPathManager.getRouterPath(routerMgmtNode.key), -1));
        return ops;
    }

    public UUID create(RouterMgmtConfig routerMgmtConfig)
            throws StateAccessException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, RouterMgmtConfig> routerMgmtNode = new ZkNodeEntry<UUID, RouterMgmtConfig>(
                id, routerMgmtConfig);
        multi(prepareRouterCreate(routerMgmtNode));
        return id;
    }

    public ZkNodeEntry<UUID, UUID> createLink(
            PortDirectory.LogicalRouterPortConfig localPort,
            PortDirectory.LogicalRouterPortConfig peerPort)
            throws StateAccessException, ZkStateSerializationException {
        // Check that they are not currently linked.
        if (exists(mgmtPathManager.getRouterRouterPath(localPort.device_id,
                peerPort.device_id))) {
            throw new IllegalArgumentException(
                    "Invalid connection.  The router ports are already connected.");
        }
        localPort.peer_uuid = ShortUUID.generate32BitUUID();
        peerPort.peer_uuid = ShortUUID.generate32BitUUID();

        ZkNodeEntry<UUID, PortDirectory.PortConfig> localPortEntry = new ZkNodeEntry<UUID, PortDirectory.PortConfig>(
                peerPort.peer_uuid, localPort);
        ZkNodeEntry<UUID, PortDirectory.PortConfig> peerPortEntry = new ZkNodeEntry<UUID, PortDirectory.PortConfig>(
                localPort.peer_uuid, peerPort);
        multi(preparePortCreateLink(localPortEntry, peerPortEntry));
        return new ZkNodeEntry<UUID, UUID>(peerPort.peer_uuid,
                localPort.peer_uuid);
    }

    public ZkNodeEntry<UUID, RouterMgmtConfig> get(UUID id)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getRouterPath(id), null);
        RouterMgmtConfig config = null;
        try {
            config = deserialize(data, RouterMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize router " + id
                            + " to RouterMgmtConfig", e, RouterMgmtConfig.class);
        }
        return new ZkNodeEntry<UUID, RouterMgmtConfig>(id, config);
    }

    public PeerRouterConfig getPeerRouterLink(UUID routerId, UUID peerRouterId)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getRouterRouterPath(routerId,
                peerRouterId), null);
        try {
            return deserialize(data, PeerRouterConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize peer router " + routerId
                            + " to PeerRouterConfig", e, PeerRouterConfig.class);
        }
    }

    public List<ZkNodeEntry<UUID, RouterMgmtConfig>> list(UUID tenantId)
            throws StateAccessException, ZkStateSerializationException {
        List<ZkNodeEntry<UUID, RouterMgmtConfig>> result = new ArrayList<ZkNodeEntry<UUID, RouterMgmtConfig>>();
        Set<String> routerIds = getChildren(mgmtPathManager
                .getTenantRoutersPath(tenantId), null);
        for (String routerId : routerIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(routerId)));
        }
        return result;
    }

    public void update(ZkNodeEntry<UUID, RouterMgmtConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        // Update any version for now.
        try {
            update(mgmtPathManager.getRouterPath(entry.key),
                    serialize(entry.value));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize router mgmt " + entry.key
                            + " to RouterMgmtConfig", e, RouterMgmtConfig.class);
        }
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareRouterDelete(id));
    }

}
