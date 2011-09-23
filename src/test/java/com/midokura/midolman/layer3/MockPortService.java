package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.quagga.ZebraServer;
import com.midokura.midolman.quagga.BgpVtyConnection;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;

import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.util.Net;

public class MockPortService implements PortService {

	private static final Logger log = LoggerFactory
			.getLogger(MockPortService.class);

	public static final String BGP_SERVICE_EXT_ID = "bgp";
	public static final short BGP_TCP_PORT = 179;
	private static final String BGP_PORT_NAME = "midobgp";

	protected OpenvSwitchDatabaseConnection ovsdb;
	// The external id key for port service.
	protected String portIdExtIdKey;
	protected String portServiceExtIdKey;

	protected NetworkController controller;

	protected PortZkManager portMgr;
	protected RouteZkManager routeMgr;
	protected BgpZkManager bgpMgr;
	protected AdRouteZkManager adRouteMgr;

	protected ZebraServer zebra;
	protected BgpVtyConnection bgpd;
	protected Runtime runtime;

	private int bgpPortIdx = 0;
	private int bgpPortNum = BGP_TCP_PORT + bgpPortIdx;

	private Map<Integer, UUID> portNumtoRemoteUUID;

	public MockPortService(OpenvSwitchDatabaseConnection ovsdb,
			String portIdExtIdKey, String portServiceExtIdKey,
			PortZkManager portMgr, RouteZkManager routeMgr,
			BgpZkManager bgpMgr, AdRouteZkManager adRouteMgr,
			ZebraServer zebra, BgpVtyConnection bgpd, Runtime runtime) {
		this.ovsdb = ovsdb;
		// "midolman_port_id"
		this.portIdExtIdKey = portIdExtIdKey;
		// "midolman_port_service"
		this.portServiceExtIdKey = portServiceExtIdKey;
		this.portMgr = portMgr;
		this.routeMgr = routeMgr;
		this.bgpMgr = bgpMgr;
		this.adRouteMgr = adRouteMgr;
		this.zebra = zebra;
		this.bgpd = bgpd;
		this.runtime = runtime;
	}

	public MockPortService(BgpZkManager bgpMgr) {
		this.bgpMgr = bgpMgr;
		this.portNumtoRemoteUUID = new HashMap<Integer, UUID>();
	}

	@Override
	public void setController(NetworkController controller) {
		this.controller = controller;
	}

	@Override
	public Set<String> getPorts(L3DevicePort port) throws StateAccessException,
			ZkStateSerializationException {
		return new HashSet<String>();
	}

	@Override
	public void addPort(long datapathId, L3DevicePort port)
			throws StateAccessException, ZkStateSerializationException {
		UUID portId = port.getId();
		// Check service attributes in port configurations.
		List<ZkNodeEntry<UUID, BgpConfig>> bgpNodes = bgpMgr.list(portId);
		for (ZkNodeEntry<UUID, BgpConfig> bgpNode : bgpNodes) {
			String portName = String.format(BGP_PORT_NAME + "%d", bgpPortIdx);
			log.debug(String.format("Add %s port %s to datapath %d",
					BGP_SERVICE_EXT_ID, portName, datapathId));
			portNumtoRemoteUUID.put(bgpPortNum, portId);
			bgpPortIdx += 1;
		}
	}

	@Override
	public UUID getRemotePort(long datapathId, short portNum, String portName) {
		UUID portId = portNumtoRemoteUUID.get((int) portNum);
		return portId;
	}

	@Override
	public void configurePort(long datapathId, UUID portId, String portName)
			throws StateAccessException, IOException {
		log.debug("Configure port " + portId.toString());
		return;
	}

	public void start(short localPortNum, L3DevicePort remotePort)
			throws StateAccessException, ZkStateSerializationException {
		UUID remotePortId = remotePort.getId();
		short remotePortNum = remotePort.getNum();
		MaterializedRouterPortConfig portConfig = remotePort.getVirtualConfig();
		int localAddr = portConfig.portAddr;

		for (ZkNodeEntry<UUID, BgpConfig> bgpNode : bgpMgr.list(remotePortId)) {
			BgpConfig bgpConfig = bgpNode.value;
			int remoteAddr = Net.convertInetAddressToInt(bgpConfig.peerAddr);
			log.debug(String.format("Port service flows: local %d remote %d "
					+ "localAddr %d remoteAddr %d "
					+ "localPort %d remotePort %d", localPortNum,
					remotePortNum, localAddr, remoteAddr, BGP_TCP_PORT,
					BGP_TCP_PORT));
			controller.setServicePortFlows(localPortNum, remotePortNum,
					localAddr, remoteAddr, BGP_TCP_PORT, BGP_TCP_PORT);
		}
	}
}