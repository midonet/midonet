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
import com.midokura.midolman.mgmt.data.state.MgmtPortZkManager;
import com.midokura.midolman.mgmt.data.state.MgmtPortZkManager.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.state.VifZkManager.VifConfig;
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

	private MgmtPortZkManager getPortZkManager() throws Exception {
		ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
		return new MgmtPortZkManager(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
	}

	private static PortMgmtConfig convertToPortMgmtConfig(Port port) {
		return new PortMgmtConfig(port.getVifId());
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
		MgmtPortZkManager manager = getPortZkManager();
		if (port instanceof LogicalRouterPort) {
			// Cannot create a single logical router object
			throw new UnsupportedOperationException(
					"Cannot create a single logical router port");
		} else if (port instanceof MaterializedRouterPort) {
			return manager
					.create(
							convertToPortMgmtConfig(port),
							convertToMatRouterPortConfig((MaterializedRouterPort) port));
		} else {
			return manager.create(convertToPortMgmtConfig(port),
					convertToBridgePortConfig(port));
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

	private static Port convertToPort(PortMgmtConfig mgmtConfig,
			BridgePortConfig config) {
		Port port = new Port();
		port.setVifId(mgmtConfig.vifId);
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

	private static Port convertToPort(PortMgmtConfig mgmtConfig,
			MaterializedRouterPortConfig config) {
		MaterializedRouterPort port = new MaterializedRouterPort();
		port.setDeviceId(config.device_id);
		port.setNetworkAddress(Net.convertIntAddressToString(config.nwAddr));
		port.setNetworkLength(config.nwLength);
		port.setPortAddress(Net.convertIntAddressToString(config.portAddr));
		port.setLocalNetworkAddress(Net
				.convertIntAddressToString(config.localNwAddr));
		port.setLocalNetworkLength(config.localNwLength);
		port.setVifId(mgmtConfig.vifId);
		return port;
	}

	private static Port convertToPort(PortMgmtConfig mgmtConfig,
			PortConfig config) {
		if (config instanceof LogicalRouterPortConfig) {
			return convertToPort((LogicalRouterPortConfig) config);
		} else if (config instanceof MaterializedRouterPortConfig) {
			return convertToPort(mgmtConfig,
					(MaterializedRouterPortConfig) config);
		} else {
			return convertToPort(mgmtConfig, (BridgePortConfig) config);
		}
	}

	public Port get(UUID id) throws Exception {
		// TODO: Throw NotFound exception here.
		MgmtPortZkManager manager = getPortZkManager();
		ZkNodeEntry<UUID, PortMgmtConfig> mgmtConfig = manager.getMgmt(id);
		ZkNodeEntry<UUID, PortConfig> config = manager.get(id);
		Port port = convertToPort(mgmtConfig.value, config.value);
		port.setId(id);
		return port;
	}

	private Port[] generatePortArray(List<ZkNodeEntry<UUID, PortConfig>> entries)
			throws Exception {
		MgmtPortZkManager manager = getPortZkManager();
		List<Port> ports = new ArrayList<Port>();
		for (ZkNodeEntry<UUID, PortConfig> entry : entries) {
			ZkNodeEntry<UUID, PortMgmtConfig> mgmt = manager.getMgmt(entry.key);
			ports.add(convertToPort(mgmt.value, entry.value));
		}
		return ports.toArray(new Port[ports.size()]);
	}

	public Port[] listBridgePorts(UUID bridgeId) throws Exception {
		return generatePortArray(getPortZkManager().listBridgePorts(bridgeId));
	}

	public void delete(UUID id) throws Exception {
		// TODO: catch NoNodeException if does not exist.
		getPortZkManager().deleteMgmt(id);
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

	public void attachVif(UUID id, Port port) throws InterruptedException,
			KeeperException, ZkStateSerializationException,
			ClassNotFoundException, Exception {
		ZkNodeEntry<UUID, PortMgmtConfig> mgmt = new ZkNodeEntry<UUID, PortMgmtConfig>(
				id, convertToPortMgmtConfig(port));
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
