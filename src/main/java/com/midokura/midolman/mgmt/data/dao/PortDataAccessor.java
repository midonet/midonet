/*
 * @(#)PortDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.state.PortZkManagerProxy;
import com.midokura.midolman.state.ZkConnection;

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

    public UUID create(Port port) throws Exception {
        if (port instanceof LogicalRouterPort) {
            // Cannot create a single logical router object
            throw new UnsupportedOperationException(
                    "Cannot create a single logical router port");
        }
        return getPortZkManager().create(port);
    }

    public Port get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return getPortZkManager().get(id);
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
    public List<Port> listBridgePorts(UUID bridgeId) throws Exception {
        return getPortZkManager().listBridgePorts(bridgeId);
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
    public List<Port> listRouterPorts(UUID routerId) throws Exception {
        return getPortZkManager().listRouterPorts(routerId);
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getPortZkManager().delete(id);
    }

}
