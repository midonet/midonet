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

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
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
    public PortDataAccessor(String zkConn, int timeout, String rootPath,
			String mgmtRootPath) {
		super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private PortZkManager getPortZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new PortZkManager(conn.getZooKeeper(), zkRoot);
    }

    private static BridgePortConfig convertToBridgePortConfig(Port port) {
        return new BridgePortConfig(port.getDeviceId());
    }

    private static LogicalRouterPortConfig convertToLogRouterPortConfig(
            LogicalRouterPort port) {
        return new LogicalRouterPortConfig(port.getDeviceId(), Net
                .convertStringAddressToInt(port.getNetworkAddress()), port
                .getNetworkLength(), Net.convertStringAddressToInt(port
                .getPortAddress()), new HashSet<Route>(), null);
    }

    private static MaterializedRouterPortConfig convertToMatRouterPortConfig(
            MaterializedRouterPort port) {
        return new MaterializedRouterPortConfig(port.getDeviceId(), Net
                .convertStringAddressToInt(port.getNetworkAddress()), port
                .getNetworkLength(), Net.convertStringAddressToInt(port
                .getPortAddress()), new HashSet<Route>(), Net
                .convertStringAddressToInt(port.getLocalNetworkAddress()), port
                .getLocalNetworkLength(), new HashSet<BGP>());
    }

    public UUID create(Port port) throws Exception {
        PortZkManager manager = getPortZkManager();
        if (port instanceof LogicalRouterPort) {
            // Cannot create a single logical router object
            throw new UnsupportedOperationException(
                    "Cannot create a single logical router port");
        } else if (port instanceof MaterializedRouterPort) {
            return manager
                    .create(convertToMatRouterPortConfig((MaterializedRouterPort) port));
        } else {
            return manager.create(convertToBridgePortConfig(port));
        }
    }

    public LogicalRouterPort createLink(LogicalRouterPort port)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, Exception {
        PortZkManager manager = getPortZkManager();

        // Create two logical router ports
        LogicalRouterPortConfig localPort = convertToLogRouterPortConfig(port);
        LogicalRouterPortConfig peerPort = convertToLogRouterPortConfig(port);
        // Set the correct router ID and port address.
        peerPort.device_id = port.getPeerRouterId();
        peerPort.portAddr = Net.convertStringAddressToInt(port
                .getPeerPortAddress());
        ZkNodeEntry<UUID, UUID> entry = manager.createLink(localPort, peerPort);
        port.setId(entry.key);
        port.setPeerId(entry.value);
        return port;
    }

    private static Port convertToPort(BridgePortConfig config) {
        Port port = new Port();
        port.setDeviceId(config.device_id);
        return port;
    }

    private static Port convertToPort(LogicalRouterPortConfig config) {
        LogicalRouterPort port = new LogicalRouterPort();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertIntAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertIntAddressToString(config.portAddr));
        port.setPeerId(config.peer_uuid);
        return port;
    }

    private static Port convertToPort(MaterializedRouterPortConfig config) {
        MaterializedRouterPort port = new MaterializedRouterPort();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertIntAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertIntAddressToString(config.portAddr));
        port.setLocalNetworkAddress(Net
                .convertIntAddressToString(config.localNwAddr));
        port.setLocalNetworkLength(config.localNwLength);
        return port;
    }

    private static Port convertToPort(PortConfig config) {
        if (config instanceof LogicalRouterPortConfig) {
            return convertToPort((LogicalRouterPortConfig) config);
        } else if (config instanceof MaterializedRouterPortConfig) {
            return convertToPort((MaterializedRouterPortConfig) config);
        } else {
            return convertToPort((BridgePortConfig) config);
        }
    }

    private static Port convertToPort(ZkNodeEntry<UUID, PortConfig> entry) {
        Port p = convertToPort(entry.value);
        p.setId(entry.key);
        return p;
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
