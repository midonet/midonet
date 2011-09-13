/*
 * @(#)PortDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.util.Net;

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
    public PortDataAccessor(String zkConn) {
        super(zkConn);
    }

    private PortZkManager getPortZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);
        return new PortZkManager(conn.getZooKeeper(), "/midolman");
    }

    private static BridgePortConfig convertToBridgePortConfig(Port port) {
        return new BridgePortConfig(port.getDeviceId());
    }
    
    private static LogicalRouterPortConfig convertToLogRouterPortConfig(
            RouterPort port) {
        return new LogicalRouterPortConfig(port.getDeviceId(), Net
                .convertAddressToInt(port.getNetworkAddress()), port
                .getNetworkLength(), Net.convertAddressToInt(port
                .getPortAddress()), new HashSet<Route>(), port.getPeerId());
    }

    private static MaterializedRouterPortConfig convertToMatRouterPortConfig(
            RouterPort port) {
        return new MaterializedRouterPortConfig(port.getDeviceId(), Net
                .convertAddressToInt(port.getNetworkAddress()), port
                .getNetworkLength(), Net.convertAddressToInt(port
                .getPortAddress()), new HashSet<Route>(), Net
                .convertAddressToInt(port.getLocalNetworkAddress()), port
                .getLocalNetworkLength(), new HashSet<BGP>());
    }

    private UUID create(PortConfig port) throws Exception {
        PortZkManager manager = getPortZkManager();
        return manager.create(port);
    }
    
    /**
     * Add bridge port to ZooKeeper directories.
     * 
     * @param port
     *            Port object to add.
     * @throws Exception
     *             Error adding data to ZooKeeper.
     */
    public UUID create(Port port) throws Exception {
        return create(convertToBridgePortConfig(port));
    }
    
    /**
     * Add router port to ZooKeeper directories.
     * 
     * @param port
     *            Port object to add.
     * @throws Exception
     *             Error adding data to ZooKeeper.
     */
    public UUID create(RouterPort port) throws Exception {
        String type = port.getType();
        if (type.equals(RouterPort.MaterializedRouterPort)) {
            return create(convertToMatRouterPortConfig(port));
        } else {
            return create(convertToLogRouterPortConfig(port));
        }
    }


    private static Port convertToPort(BridgePortConfig config) {
        Port port = new Port();
        port.setDeviceId(config.device_id);
        return port;
    }

    private static Port convertToPort(LogicalRouterPortConfig config) {
        RouterPort port = new RouterPort();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertAddressToString(config.portAddr));
        port.setPeerId(config.peer_uuid);
        port.setType(RouterPort.LogicalRouterPort);
        return port;
    }

    private static Port convertToPort(MaterializedRouterPortConfig config) {
        RouterPort port = new RouterPort();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertAddressToString(config.portAddr));
        port.setLocalNetworkAddress(Net
                .convertAddressToString(config.localNwAddr));
        port.setLocalNetworkLength(config.localNwLength);
        port.setType(RouterPort.MaterializedRouterPort);
        return port;
    }

    private static Port convertToPort(PortConfig config) {
        if (config instanceof LogicalRouterPortConfig) {
            return convertToPort((LogicalRouterPortConfig) config);
        } else if (config instanceof MaterializedRouterPortConfig) {
            return convertToPort((MaterializedRouterPortConfig) config);
        } else if (config instanceof BridgePortConfig) {
            return convertToPort((BridgePortConfig) config);
        }
        return null;
    }


    public void delete(UUID id) throws Exception {
        PortZkManager manager = getPortZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }

    /**
     * Update Port entry in ZooKeeper.
     * 
     * @param port
     *            Port object to update.
     * @throws Exception
     *             Error adding data to ZooKeeper.
     */
    public void update(UUID id, Port port) throws Exception {
        PortZkManager manager = getPortZkManager();
        PortConfig config = convertToBridgePortConfig(port);
        manager.update(id, config);
    }

    /**
     * Get a Port for the given ID.
     * 
     * @param id
     *            Port ID to search.
     * @return Port object with the given ID.
     * @throws Exception
     *             Error getting data to Zookeeper.
     */
    public Port get(UUID id) throws Exception {
        PortZkManager manager = getPortZkManager();
        PortConfig config = manager.get(id);
        // TODO: Throw NotFound exception here.
        Port port = convertToPort(config);
        port.setId(id);
        return port;
    }

    private static Port[] generatePortArray(Map<UUID, PortConfig> configs) {
        List<Port> ports = new ArrayList<Port>();
        for (Map.Entry<UUID, PortConfig> entry : configs.entrySet()) {
            Port port = convertToPort(entry.getValue());
            port.setId(entry.getKey());
            ports.add(port);
        }
        return ports.toArray(new Port[ports.size()]);
    }

    public Port[] listBridgePorts(UUID bridgeId) throws Exception {
        PortZkManager manager = getPortZkManager();
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
        PortZkManager manager = getPortZkManager();
        return generatePortArray(manager.listRouterPorts(routerId));
    }
}
