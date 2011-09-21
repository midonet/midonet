/*
 * @(#)PortZkManagerProxy        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.mgmt.data.state.VifZkManager.VifConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.PortConfig;

/**
 * ZK port management class.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortZkManagerProxy extends ZkMgmtManager {

    public static class PortMgmtConfig {

        public PortMgmtConfig() {
            super();
        }

        public PortMgmtConfig(UUID vifId) {
            super();
            this.vifId = vifId;
        }

        public UUID vifId = null;
    }

    private PortZkManager zkManager = null;

    public PortZkManagerProxy(ZooKeeper zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new PortZkManager(zk, basePath);
    }

    public List<Op> preparePortCreate(
            MgmtNode<UUID, PortMgmtConfig, PortConfig> node)
            throws ZkStateSerializationException {
        return preparePortCreate(node, null);
    }

    public List<Op> preparePortCreate(
            MgmtNode<UUID, PortMgmtConfig, PortConfig> node,
            ZkNodeEntry<UUID, VifConfig> vifNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(mgmtPathManager.getPortPath(node.key),
                    serialize(node.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));

        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + node.key
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }

        // Create PortConfig
        ops.addAll(zkManager.preparePortCreate(node.key, node.extra));

        // If VIF is set, then create a new VIF entry.
        if (vifNode != null) {
            try {
                ops.add(Op.create(mgmtPathManager.getVifPath(vifNode.key),
                        serialize(vifNode.value), Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT));

            } catch (IOException e) {
                throw new ZkStateSerializationException(
                        "Could not serialize vif " + vifNode.key
                                + " to VifConfig", e, VifConfig.class);
            }
        }
        return ops;
    }

    public List<Op> preparePortVifAttach(
            ZkNodeEntry<UUID, PortMgmtConfig> portNode,
            ZkNodeEntry<UUID, VifConfig> vifNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.setData(mgmtPathManager.getPortPath(portNode.key),
                    serialize(portNode.value), -1));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + portNode.key
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }
        try {
            ops.add(Op.create(mgmtPathManager.getVifPath(vifNode.key),
                    serialize(vifNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));

        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize vif "
                    + vifNode.key + " to VifConfig", e, VifConfig.class);
        }
        return ops;
    }

    public List<Op> preparePortVifDettach(UUID portId)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {

        ZkNodeEntry<UUID, PortMgmtConfig> portNode = get(portId);
        UUID vifId = portNode.value.vifId;
        portNode.value.vifId = null;

        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.setData(mgmtPathManager.getPortPath(portNode.key),
                    serialize(portNode.value), -1));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + portNode.key
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }

        if (vifId != null) {
            ops.add(Op.delete(mgmtPathManager.getVifPath(vifId), -1));
        }
        return ops;
    }

    public List<Op> preparePortDelete(UUID id) throws KeeperException,
            InterruptedException, ClassNotFoundException,
            ZkStateSerializationException {
        return preparePortDelete(id, true);
    }

    public List<Op> preparePortDelete(UUID id, boolean cascade)
            throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException {
        return preparePortDelete(get(id), cascade);
    }

    public List<Op> preparePortDelete(
            ZkNodeEntry<UUID, PortMgmtConfig> portMgmtNode)
            throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException {
        return preparePortDelete(portMgmtNode, true);
    }

    public List<Op> preparePortDelete(
            ZkNodeEntry<UUID, PortMgmtConfig> portMgmtNode, boolean cascade)
            throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Prepare the midolman port deletion.
        if (cascade) {
            ZkNodeEntry<UUID, PortConfig> portNode = zkManager
                    .get(portMgmtNode.key);
            ops.addAll(zkManager.preparePortDelete(portNode));
        }

        // Delete the VIF attached
        ops.add(Op.delete(mgmtPathManager.getVifPath(portMgmtNode.value.vifId),
                -1));

        // Delete management port
        ops.add(Op.delete(mgmtPathManager.getPortPath(portMgmtNode.key), -1));

        return ops;
    }

    private ZkNodeEntry<UUID, PortMgmtConfig> getMgmt(UUID id)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] data = zk.get(mgmtPathManager.getPortPath(id), null);
        PortMgmtConfig mgmtConfig = null;
        try {
            mgmtConfig = deserialize(data, PortMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize port " + id + " to PortMgmtConfig",
                    e, PortMgmtConfig.class);
        }
        return new ZkNodeEntry<UUID, PortMgmtConfig>(id, mgmtConfig);
    }

    public MgmtNode<UUID, PortMgmtConfig, PortConfig> get(UUID id)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        ZkNodeEntry<UUID, PortMgmtConfig> mgmtNode = getMgmt(id);
        ZkNodeEntry<UUID, PortConfig> node = zkManager.get(id);
        return new MgmtNode<UUID, PortMgmtConfig, PortConfig>(id,
                mgmtNode.value, node.value);
    }

    private List<MgmtNode<UUID, PortMgmtConfig, PortConfig>> generateList (
            List<ZkNodeEntry<UUID, PortConfig>> nodes) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        List<MgmtNode<UUID, PortMgmtConfig, PortConfig>> portNodes = new ArrayList<MgmtNode<UUID, PortMgmtConfig, PortConfig>>();
        for (ZkNodeEntry<UUID, PortConfig> node : nodes) {
            ZkNodeEntry<UUID, PortMgmtConfig> mgmtNode = getMgmt(node.key);
            portNodes.add(new MgmtNode<UUID, PortMgmtConfig, PortConfig>(
                    node.key, mgmtNode.value, node.value));
        }
        return portNodes;
    }

    public List<MgmtNode<UUID, PortMgmtConfig, PortConfig>> listRouterPorts(
            UUID routerId) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return generateList(zkManager.listRouterPorts(routerId));
    }

    public List<MgmtNode<UUID, PortMgmtConfig, PortConfig>> listBridgePorts(
            UUID bridgeId) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return generateList(zkManager.listBridgePorts(bridgeId));
    }

    public UUID create(PortMgmtConfig portMgmt,
            PortDirectory.PortConfig portConfig) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        MgmtNode<UUID, PortMgmtConfig, PortConfig> node = new MgmtNode<UUID, PortMgmtConfig, PortConfig>(
                id, portMgmt, portConfig);
        zk.multi(preparePortCreate(node));
        return id;
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            ZkStateSerializationException, ClassNotFoundException {
        this.zk.multi(preparePortDelete(id));
    }

    public void attachVif(ZkNodeEntry<UUID, PortMgmtConfig> portNode,
            ZkNodeEntry<UUID, VifConfig> vifNode) throws InterruptedException,
            KeeperException, ZkStateSerializationException,
            ClassNotFoundException {
        this.zk.multi(preparePortVifAttach(portNode, vifNode));
    }

    public void detachVif(UUID id) throws InterruptedException,
            KeeperException, ZkStateSerializationException,
            ClassNotFoundException {
        this.zk.multi(preparePortVifDettach(id));
    }
}
