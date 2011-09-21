/*
 * @(#)PortDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.state.MgmtNode;
import com.midokura.midolman.mgmt.data.state.PortZkManagerProxy;
import com.midokura.midolman.mgmt.data.state.PortZkManagerProxy.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.state.VifZkManager.VifConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;

/**
 * Data access class for port.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            ZooKeeper connection string
     */
    public PortDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private PortZkManagerProxy getPortZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new PortZkManagerProxy(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
    }

    private static Port createPort(UUID id, PortMgmtConfig mgmtConfig,
            PortConfig config) {
        if (config instanceof LogicalRouterPortConfig) {
            return LogicalRouterPort.createPort(id,
                    (LogicalRouterPortConfig) config);
        } else if (config instanceof MaterializedRouterPortConfig) {
            return MaterializedRouterPort.createPort(id, mgmtConfig,
                    (MaterializedRouterPortConfig) config);
        } else {
            return Port.createPort(id, mgmtConfig, (BridgePortConfig) config);
        }
    }

    public UUID create(Port port) throws Exception {
        PortZkManagerProxy manager = getPortZkManager();
        if (port instanceof LogicalRouterPort) {
            // Cannot create a single logical router object
            throw new UnsupportedOperationException(
                    "Cannot create a single logical router port");
        } else if (port instanceof MaterializedRouterPort) {
            PortMgmtConfig mgmtConfig = port.toPortMgmtConfig();
            PortConfig config = ((MaterializedRouterPort) port)
                    .toMaterializedRouterPortConfig();
            return manager.create(mgmtConfig, config);
        } else {
            return manager.create(port.toPortMgmtConfig(), port
                    .toBridgePortConfig());
        }
    }

    public Port get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        PortZkManagerProxy manager = getPortZkManager();
        MgmtNode<UUID, PortMgmtConfig, PortConfig> node = manager.get(id);
        return createPort(id, node.value, node.extra);
    }

    private Port[] generatePortArray(
            List<MgmtNode<UUID, PortMgmtConfig, PortConfig>> entries)
            throws Exception {
        List<Port> ports = new ArrayList<Port>();
        for (MgmtNode<UUID, PortMgmtConfig, PortConfig> entry : entries) {
            ports.add(createPort(entry.key, entry.value, entry.extra));
        }
        return ports.toArray(new Port[ports.size()]);
    }

    /**
     * Get a list of Ports for a bridge.
     * 
     * @param routerId
     *            UUID of router.
     * @return A Set of Ports
     * @throws Exception
     *             Zookeeper(or any) error.
     */
    public Port[] listBridgePorts(UUID bridgeId) throws Exception {
        PortZkManagerProxy manager = getPortZkManager();
        return generatePortArray(manager.listBridgePorts(bridgeId));
    }

    /**
     * Get a list of Ports for a router.
     * 
     * @param routerId
     *            UUID of router.
     * @return A Set of Ports
     * @throws Exception
     *             Zookeeper(or any) error.
     */
    public Port[] listRouterPorts(UUID routerId) throws Exception {
        PortZkManagerProxy manager = getPortZkManager();
        return generatePortArray(manager.listRouterPorts(routerId));
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getPortZkManager().delete(id);
    }

    public void attachVif(UUID id, Port port) throws InterruptedException,
            KeeperException, ZkStateSerializationException,
            ClassNotFoundException, Exception {
        ZkNodeEntry<UUID, PortMgmtConfig> mgmt = new ZkNodeEntry<UUID, PortMgmtConfig>(
                id, port.toPortMgmtConfig());
        VifConfig vifConfig = new VifConfig(id);
        getPortZkManager().attachVif(mgmt,
                new ZkNodeEntry<UUID, VifConfig>(port.getVifId(), vifConfig));
    }

    public void detachVif(UUID id) throws InterruptedException,
            KeeperException, ZkStateSerializationException,
            ClassNotFoundException, Exception {
        getPortZkManager().detachVif(id);
    }

}
