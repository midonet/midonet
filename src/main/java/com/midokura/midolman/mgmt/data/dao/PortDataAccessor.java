/*
 * @(#)PortDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
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

    public UUID create(Port port) throws Exception {
        return create(convertToBridgePortConfig(port));
    }

    public UUID create(RouterPort port) throws Exception {
        String type = port.getType();
        if (type.equals(RouterPort.Materialized)) {
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
        port.setType(RouterPort.Logical);
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
        port.setType(RouterPort.Materialized);
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

    private static Port convertToPort(ZkNodeEntry<UUID, PortConfig> entry) {
        Port p = convertToPort(entry.value);
        p.setId(entry.key);
        return p;
    }

    private static void copyLogicalRouterPort(RouterPort port,
            LogicalRouterPortConfig config) {
        // Just allow copy of the peerId
        config.peer_uuid = port.getPeerId();
    }

    public Port get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return convertToPort(getPortZkManager().get(id));
    }

    private static Port[] generatePortArray(
            List<ZkNodeEntry<UUID, PortConfig>> entries) {
        List<Port> ports = new ArrayList<Port>();
        for (ZkNodeEntry<UUID, PortConfig> entry : entries) {
            ports.add(convertToPort(entry));
        }
        return ports.toArray(new Port[ports.size()]);
    }

    public Port[] listBridgePorts(UUID bridgeId) throws Exception {
        return generatePortArray(getPortZkManager().listBridgePorts(bridgeId));
    }

    public void update(UUID id, Port port) throws Exception {
        // Only allow an update of LogicalRouterPort's peer ID field.
        PortZkManager manager = getPortZkManager();
        ZkNodeEntry<UUID, PortConfig> entry = manager.get(id);
        if (!(entry.value instanceof LogicalRouterPortConfig && port instanceof RouterPort)) {
            throw new UnsupportedOperationException(
                    "Only LogicalRouterPort can be updated.");
        }
        copyLogicalRouterPort((RouterPort) port,
                (LogicalRouterPortConfig) entry.value);
        manager.update(entry);
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getPortZkManager().delete(id);
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
