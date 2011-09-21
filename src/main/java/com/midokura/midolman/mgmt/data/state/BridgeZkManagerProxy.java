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
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;

/**
 * Class to manage the bridge ZooKeeper data.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeZkManagerProxy extends ZkMgmtManager {

    public static class BridgeMgmtConfig {

        public BridgeMgmtConfig() {
            super();
        }

        public BridgeMgmtConfig(UUID tenantId, String name) {
            super();
            this.tenantId = tenantId;
            this.name = name;
        }

        public UUID tenantId;
        public String name;
    }

    private BridgeZkManager zkManager = null;

    public BridgeZkManagerProxy(ZooKeeper zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new BridgeZkManager(zk, basePath);
    }

    public List<Op> prepareBridgeCreate(UUID id, BridgeMgmtConfig config)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        ZkNodeEntry<UUID, BridgeMgmtConfig> node = new ZkNodeEntry<UUID, BridgeMgmtConfig>(
                id, config);
        return prepareBridgeCreate(node);
    }

    public List<Op> prepareBridgeCreate(ZkNodeEntry<UUID, BridgeMgmtConfig> node)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        List<Op> ops = new ArrayList<Op>();

        // Add an entry.
        try {
            ops.add(Op.create(mgmtPathManager.getBridgePath(node.key),
                    serialize(node.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeMgmtConfig", e,
                    BridgeMgmtConfig.class);
        }

        // Add under tenant.
        ops.add(Op.create(mgmtPathManager.getTenantBridgePath(
                node.value.tenantId, node.key), null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.addAll(zkManager.prepareBridgeCreate(node.key, new BridgeConfig()));
        return ops;
    }

    public List<Op> prepareBridgeDelete(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException,
            ClassNotFoundException {
        return prepareBridgeDelete(get(id));
    }

    public List<Op> prepareBridgeDelete(
            ZkNodeEntry<UUID, BridgeMgmtConfig> bridgeMgmtNode)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, ClassNotFoundException {
        List<Op> ops = new ArrayList<Op>();
        ZkNodeEntry<UUID, BridgeConfig> bridgeNode = zkManager
                .get(bridgeMgmtNode.key);
        try {
            ops.addAll(zkManager.prepareBridgeDelete(bridgeNode));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize BridgeConfig", e, BridgeConfig.class);
        }

        // Delete the tenant bridge entry
        ops.add(Op.delete(mgmtPathManager.getTenantBridgePath(
                bridgeMgmtNode.value.tenantId, bridgeMgmtNode.key), -1));
        ops.add(Op
                .delete(mgmtPathManager.getBridgePath(bridgeMgmtNode.key), -1));

        // Remove all the ports in mgmt directory but don't cascade here.
        PortZkManagerProxy portMgr = new PortZkManagerProxy(zooKeeper,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        PortZkManager portZkManager = new PortZkManager(zk, pathManager
                .getBasePath());
        List<ZkNodeEntry<UUID, PortConfig>> portNodes = portZkManager
                .listBridgePorts(bridgeMgmtNode.key);
        for (ZkNodeEntry<UUID, PortConfig> portNode : portNodes) {
            ops.addAll(portMgr.preparePortDelete(portNode.key, false));
        }

        return ops;
    }

    public UUID create(BridgeMgmtConfig bridgeMgmtConfig)
            throws InterruptedException, KeeperException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareBridgeCreate(id, bridgeMgmtConfig));
        return id;
    }

    public ZkNodeEntry<UUID, BridgeMgmtConfig> get(UUID id)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] data = zk.get(mgmtPathManager.getBridgePath(id), null);
        BridgeMgmtConfig config = null;
        try {
            config = deserialize(data, BridgeMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize bridge " + id
                            + " to BridgeMgmtConfig", e, BridgeMgmtConfig.class);
        }
        return new ZkNodeEntry<UUID, BridgeMgmtConfig>(id, config);
    }

    public List<ZkNodeEntry<UUID, BridgeMgmtConfig>> list(UUID tenantId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, BridgeMgmtConfig>> result = new ArrayList<ZkNodeEntry<UUID, BridgeMgmtConfig>>();
        Set<String> bridgeIds = zk.getChildren(mgmtPathManager
                .getTenantBridgesPath(tenantId), null);
        for (String bridgeId : bridgeIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(bridgeId)));
        }
        return result;
    }

    public void update(ZkNodeEntry<UUID, BridgeMgmtConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Update any version for now.
        try {
            zk.update(mgmtPathManager.getBridgePath(entry.key),
                    serialize(entry.value));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize bridge mgmt " + entry.key
                            + " to BridgeMgmtConfig", e, BridgeMgmtConfig.class);
        }
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            ZkStateSerializationException, IOException, ClassNotFoundException {
        this.zk.multi(prepareBridgeDelete(id));
    }
}
