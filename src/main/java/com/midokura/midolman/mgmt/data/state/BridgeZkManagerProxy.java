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

import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.StateAccessException;
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
            throws StateAccessException, ZkStateSerializationException {
        ZkNodeEntry<UUID, BridgeMgmtConfig> node = new ZkNodeEntry<UUID, BridgeMgmtConfig>(
                id, config);
        return prepareBridgeCreate(node);
    }

    public List<Op> prepareBridgeCreate(ZkNodeEntry<UUID, BridgeMgmtConfig> node)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        byte[] data = null;
        try {
            serialize(node.value);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while preparing bridge creation multi ops for UUID "
                            + node.key, e, BridgeMgmtConfig.class);
        }

        // Add an entry.
        ops.add(Op.create(mgmtPathManager.getBridgePath(node.key), data,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // Add under tenant.
        ops.add(Op.create(mgmtPathManager.getTenantBridgePath(
                node.value.tenantId, node.key), null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.addAll(zkManager.prepareBridgeCreate(node.key, new BridgeConfig()));

        return ops;
    }

    public List<Op> prepareBridgeDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareBridgeDelete(get(id));
    }

    public List<Op> prepareBridgeDelete(
            ZkNodeEntry<UUID, BridgeMgmtConfig> bridgeMgmtNode)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ZkNodeEntry<UUID, BridgeConfig> bridgeNode = zkManager
                .get(bridgeMgmtNode.key);
        ops.addAll(zkManager.prepareBridgeDelete(bridgeNode));

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
            throws StateAccessException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        multi(prepareBridgeCreate(id, bridgeMgmtConfig));
        return id;
    }

    public ZkNodeEntry<UUID, BridgeMgmtConfig> get(UUID id)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getBridgePath(id));
        BridgeMgmtConfig config = null;
        try {
            config = deserialize(data, BridgeMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while getting the bridge with UUID "
                            + id, e, BridgeMgmtConfig.class);
        }
        return new ZkNodeEntry<UUID, BridgeMgmtConfig>(id, config);
    }

    public List<ZkNodeEntry<UUID, BridgeMgmtConfig>> list(UUID tenantId)
            throws StateAccessException, ZkStateSerializationException {
        List<ZkNodeEntry<UUID, BridgeMgmtConfig>> result = new ArrayList<ZkNodeEntry<UUID, BridgeMgmtConfig>>();
        String path = mgmtPathManager.getTenantBridgesPath(tenantId);
        Set<String> ids = getChildren(path);
        for (String id : ids) {
            // For now, get each one.
            result.add(get(UUID.fromString(id)));
        }
        return result;
    }

    public void update(ZkNodeEntry<UUID, BridgeMgmtConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = null;
        try {
            data = serialize(entry.value);

        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while updating the bridge with UUID "
                            + entry.key, e, BridgeMgmtConfig.class);
        }
        update(mgmtPathManager.getBridgePath(entry.key), data);
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareBridgeDelete(id));
    }
}
