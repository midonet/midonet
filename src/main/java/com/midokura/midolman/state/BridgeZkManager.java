/*
 * @(#)BridgeZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.GreZkManager.GreKey;

/**
 * ZK bridge management class.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeZkManager extends ZkManager {

    public static class BridgeConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        public BridgeConfig() {
        }

        public BridgeConfig(String name, UUID tenantId) {
            this(name, tenantId, -1);
        }

        public BridgeConfig(String name, UUID tenantId, int greKey) {
            super();
            this.name = name;
            this.tenantId = tenantId;
            this.greKey = greKey;
        }

        public String name;
        public UUID tenantId;
        public int greKey;
    }

    /**
     * BridgeZkManager constructor.
     * 
     * @param zk
     *            Zookeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public BridgeZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

    public List<Op> prepareBridgeCreate(
            ZkNodeEntry<UUID, BridgeConfig> bridgeNode)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {

        // Create a new GRE key. Hide this from outside.
        GreZkManager greZk = new GreZkManager(zk, basePath);
        ZkNodeEntry<Integer, GreKey> gre = greZk.createGreKey();
        bridgeNode.value.greKey = gre.key;

        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getBridgePath(bridgeNode.key),
                    serialize(bridgeNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeConfig", e, BridgeConfig.class);
        }
        ops.add(Op.create(pathManager.getTenantBridgePath(
                bridgeNode.value.tenantId, bridgeNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getBridgePortsPath(bridgeNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Update GreKey to reference the bridge.
        gre.value.bridgeId = bridgeNode.key;
        ops.addAll(greZk.prepareGreUpdate(gre));
        return ops;
    }

    public UUID create(BridgeConfig bridge) throws InterruptedException,
            KeeperException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, BridgeConfig> bridgeNode = new ZkNodeEntry<UUID, BridgeConfig>(
                id, bridge);
        zk.multi(prepareBridgeCreate(bridgeNode));
        return id;
    }

    public ZkNodeEntry<UUID, BridgeConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.getData(pathManager.getBridgePath(id), null, null);
        BridgeConfig config = null;
        try {
            config = deserialize(data, BridgeConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize bridge " + id + " to BridgeConfig",
                    e, BridgeConfig.class);
        }
        return new ZkNodeEntry<UUID, BridgeConfig>(id, config);
    }

    public List<ZkNodeEntry<UUID, BridgeConfig>> list(UUID tenantId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, BridgeConfig>> result = new ArrayList<ZkNodeEntry<UUID, BridgeConfig>>();
        List<String> bridgeIds = zk.getChildren(pathManager
                .getTenantBridgesPath(tenantId), null);
        for (String bridgeId : bridgeIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(bridgeId)));
        }
        return result;
    }

    public void update(ZkNodeEntry<UUID, BridgeConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Update any version for now.
        try {
            zk.setData(pathManager.getBridgePath(entry.key),
                    serialize(entry.value), -1);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize bridge " + entry.key
                            + " to BridgeConfig", e, BridgeConfig.class);
        }
    }
}
