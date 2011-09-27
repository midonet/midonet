/*
 * @(#)PortZkManagerProxy        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.util.ShortUUID;

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
    private final static Logger log = LoggerFactory
            .getLogger(PortZkManagerProxy.class);

    public PortZkManagerProxy(ZooKeeper zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new PortZkManager(zk, basePath);
    }

    public List<Op> prepareCreate(Port port)
            throws ZkStateSerializationException, UnsupportedOperationException {
        if (port instanceof LogicalRouterPort) {
            // Cannot create a single logical router object
            throw new UnsupportedOperationException(
                    "Cannot create a single logical router port");
        }
        List<Op> ops = new ArrayList<Op>();
        String portPath = mgmtPathManager.getPortPath(port.getId());
        log.debug("Preparing to create: " + portPath);
        try {
            ops.add(Op.create(portPath, serialize(port.toMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + port.getId()
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }

        // Create PortConfig
        log.debug("Preparing to create Midolman config.");
        ops.addAll(zkManager.preparePortCreate(port.getId(), port.toConfig()));

        return ops;
    }

    public List<Op> prepareBridgeDelete(UUID bridgeId)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Get all the bridge ports to delete.
        List<Port> ports = listBridgePorts(bridgeId);
        for (Port port : ports) {
            ops.addAll(prepareDelete(port, false));
        }

        return ops;
    }

    public List<Op> prepareVifAttach(Port port)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        String portPath = mgmtPathManager.getPortPath(port.getId());
        log.debug("Preparing to update: " + portPath);
        try {
            ops.add(Op.setData(portPath, serialize(port.toMgmtConfig()), -1));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + port.getId()
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }
        return ops;
    }

    public List<Op> prepareVifDettach(UUID portId)
            throws ZkStateSerializationException, StateAccessException {

        Port port = get(portId);
        port.setVifId(null);

        List<Op> ops = new ArrayList<Op>();
        String portPath = mgmtPathManager.getPortPath(port.getId());

        log.debug("Preparing to update: " + portPath);
        try {
            ops.add(Op.setData(portPath, serialize(port.toConfig()), -1));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + port.getId()
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }

        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareDelete(id, true);
    }

    public List<Op> prepareDelete(UUID id, boolean cascade)
            throws StateAccessException, ZkStateSerializationException {
        return prepareDelete(get(id), cascade);
    }

    public List<Op> prepareDelete(Port port) throws StateAccessException,
            ZkStateSerializationException {
        return prepareDelete(port, true);
    }

    public List<Op> prepareDelete(Port port, boolean cascade)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Prepare the midolman port deletion.
        if (cascade) {
            ZkNodeEntry<UUID, PortConfig> portNode = zkManager
                    .get(port.getId());
            ops.addAll(zkManager.preparePortDelete(portNode));
        }

        // Delete the VIF attache
        if (port.getVifId() != null) {
            VifZkManager vifManager = new VifZkManager(zooKeeper, pathManager
                    .getBasePath(), mgmtPathManager.getBasePath());
            ops.addAll(vifManager.prepareDelete(port.getVifId()));
        }

        // Delete management port
        String portPath = mgmtPathManager.getPortPath(port.getId());
        log.debug("Preparing to delete: " + portPath);
        ops.add(Op.delete(portPath, -1));

        return ops;
    }

    private ZkNodeEntry<UUID, PortMgmtConfig> getMgmt(UUID id)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getPortPath(id), null);
        PortMgmtConfig mgmtConfig = null;
        if (data != null) {
            try {
                mgmtConfig = deserialize(data, PortMgmtConfig.class);
            } catch (IOException e) {
                throw new ZkStateSerializationException(
                        "Could not deserialize port " + id
                                + " to PortMgmtConfig", e, PortMgmtConfig.class);
            }
        }
        return new ZkNodeEntry<UUID, PortMgmtConfig>(id, mgmtConfig);
    }

    private Port createPort(ZkNodeEntry<UUID, PortMgmtConfig> mgmtNode,
            ZkNodeEntry<UUID, PortConfig> node) {
        UUID id = mgmtNode.key;
        if (node.value instanceof LogicalRouterPortConfig) {
            return LogicalRouterPort.createPort(id,
                    (LogicalRouterPortConfig) node.value);
        } else if (node.value instanceof MaterializedRouterPortConfig) {
            return MaterializedRouterPort.createPort(id, mgmtNode.value,
                    (MaterializedRouterPortConfig) node.value);
        } else {
            return Port.createPort(id, mgmtNode.value, node.value);
        }
    }

    public Port get(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        ZkNodeEntry<UUID, PortMgmtConfig> mgmtNode = getMgmt(id);
        ZkNodeEntry<UUID, PortConfig> node = zkManager.get(id);
        return createPort(mgmtNode, node);
    }

    private List<Port> generateList(List<ZkNodeEntry<UUID, PortConfig>> nodes)
            throws StateAccessException, ZkStateSerializationException {
        List<Port> portNodes = new ArrayList<Port>();
        for (ZkNodeEntry<UUID, PortConfig> node : nodes) {
            ZkNodeEntry<UUID, PortMgmtConfig> mgmtNode = getMgmt(node.key);
            portNodes.add(createPort(mgmtNode, node));
        }
        return portNodes;
    }

    public List<Port> listRouterPorts(UUID routerId)
            throws StateAccessException, ZkStateSerializationException {
        return generateList(zkManager.listRouterPorts(routerId));
    }

    public List<Port> listBridgePorts(UUID bridgeId)
            throws StateAccessException, ZkStateSerializationException {
        return generateList(zkManager.listBridgePorts(bridgeId));
    }

    public UUID create(Port port) throws StateAccessException,
            ZkStateSerializationException, UnsupportedOperationException {
        UUID id = ShortUUID.generate32BitUUID();
        port.setId(id);
        multi(prepareCreate(port));
        return id;
    }

    public boolean exists(UUID id) throws StateAccessException {
        return super.exists(mgmtPathManager.getPortPath(id));
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareDelete(id));
    }
}
