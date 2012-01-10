/*
 * @(#)PortZkManagerProxy        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.ShortUUID;

/**
 * ZK port management class.
 *
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortZkManagerProxy extends ZkMgmtManager implements PortDao,
        OwnerQueryable {

    private PortZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(PortZkManagerProxy.class);

    public PortZkManagerProxy(Directory zk, String basePath, String mgmtBasePath) {
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
            throws StateAccessException, ZkStateSerializationException,
            UnsupportedOperationException {
        List<Op> ops = new ArrayList<Op>();

        // Get all the bridge ports to delete.
        List<Port> ports = listBridgePorts(bridgeId);
        for (Port port : ports) {
            ops.addAll(prepareDelete(port, false, false));
        }

        return ops;
    }

    public List<Op> prepareVifAttach(Port port)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        String portPath = mgmtPathManager.getPortPath(port.getId());
        log.debug("Preparing to update: " + portPath + " with vif "
                + port.getVifId());
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

        log.debug("Preparing to update: " + portPath + " with vif "
                + port.getVifId());
        try {
            ops.add(Op.setData(portPath, serialize(port.toMgmtConfig()), -1));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize port mgmt " + port.getId()
                            + " to PortMgmtConfig", e, PortMgmtConfig.class);
        }

        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException, UnsupportedOperationException {
        return prepareDelete(id, true);
    }

    public List<Op> prepareDelete(UUID id, boolean cascade)
            throws StateAccessException, ZkStateSerializationException,
            UnsupportedOperationException {
        return prepareDelete(id, cascade, false);
    }

    public List<Op> prepareDelete(UUID id, boolean cascade, boolean force)
            throws StateAccessException, ZkStateSerializationException,
            UnsupportedOperationException {
        return prepareDelete(get(id), cascade, force);
    }

    public List<Op> prepareDelete(Port port) throws StateAccessException,
            ZkStateSerializationException, UnsupportedOperationException {
        return prepareDelete(port, true, false);
    }

    public List<Op> prepareDelete(Port port, boolean cascade, boolean force)
            throws StateAccessException, ZkStateSerializationException,
            UnsupportedOperationException {
        // Don't let a port that has a vif plugged in get deleted.
        if (port.getVifId() != null) {
            throw new IllegalArgumentException(
                    "Cannot delete a port with VIF plugged in.");
        }

        if (!force) {
            if (port instanceof LogicalRouterPort) {
                throw new UnsupportedOperationException(
                        "Cannot delete a logical port without deleting the link.");
            }
        }
        List<Op> ops = new ArrayList<Op>();

        // Prepare the midolman port deletion.
        if (cascade) {
            ZkNodeEntry<UUID, PortConfig> portNode = zkManager
                    .get(port.getId());
            ops.addAll(zkManager.preparePortDelete(portNode));
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
        if (node.value instanceof PortDirectory.LogicalRouterPortConfig) {
            return new LogicalRouterPort(id,
                    (PortDirectory.LogicalRouterPortConfig) node.value);
        } else if (node.value instanceof PortDirectory.MaterializedRouterPortConfig) {
            return new MaterializedRouterPort(id, mgmtNode.value,
                    (PortDirectory.MaterializedRouterPortConfig) node.value);
        } else {
            return new BridgePort(id, mgmtNode.value, node.value);
        }
    }

    @Override
    public Port get(UUID id) throws StateAccessException {
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

    @Override
    public List<Port> listRouterPorts(UUID routerId)
            throws StateAccessException {
        return generateList(zkManager.listRouterPorts(routerId));
    }

    @Override
    public List<Port> listBridgePorts(UUID bridgeId)
            throws StateAccessException {
        return generateList(zkManager.listBridgePorts(bridgeId));
    }

    @Override
    public UUID create(Port port) throws StateAccessException {
        UUID id = ShortUUID.generate32BitUUID();
        port.setId(id);
        multi(prepareCreate(port));
        return id;
    }

    @Override
    public boolean exists(UUID id) throws StateAccessException {
        return super.exists(mgmtPathManager.getPortPath(id));
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        multi(prepareDelete(id));
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        Port port = get(id);
        if (port instanceof RouterPort) {
            OwnerQueryable dao = new RouterZkManagerProxy(zk,
                    pathManager.getBasePath(), mgmtPathManager.getBasePath());
            return dao.getOwner(port.getDeviceId());
        } else {
            OwnerQueryable dao = new BridgeZkManagerProxy(zk,
                    pathManager.getBasePath(), mgmtPathManager.getBasePath());
            return dao.getOwner(port.getDeviceId());
        }
    }

    @Override
    public Port getByAdRoute(UUID adRouteId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Port getByBgp(UUID bgpId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Port getByVpn(UUID vpnId) throws StateAccessException {
        // TODO Auto-generated method stub
        return null;
    }
}
