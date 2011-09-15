// Copyright 2011 Midokura Inc.

package com.midokura.midolman;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.BridgeController;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.MockDirectory;


import java.util.UUID;
import java.net.InetAddress;
import java.net.UnknownHostException;


public class TestBridgeController {

    private BridgeController controller;

    private MockDirectory portLocDir, macPortDir;
    private PortToIntNwAddrMap portLocMap;
    private MacPortMap macPortMap;
    private MockOpenvSwitchDatabaseConnection ovsdb;
    private InetAddress publicIp;

    @Before
    public void setUp() throws UnknownHostException {
	portLocDir = new MockDirectory();
	portLocMap = new PortToIntNwAddrMap(portLocDir);
	macPortDir = new MockDirectory();
	macPortMap = new MacPortMap(macPortDir);
	ovsdb = new MockOpenvSwitchDatabaseConnection();
	publicIp = InetAddress.getByAddress(new byte[] { (byte)192, (byte)168, (byte)1, (byte)50 });

	controller = new BridgeController(
		/* datapathId */		43, 
		/* switchUuid */		UUID.randomUUID(),
		/* greKey */			0xe1234,
		/* port_loc_map */		portLocMap,
		/* mac_port_map */		macPortMap,
		/* flowExpireMinMillis */	260*1000,
		/* flowExpireMaxMillis */	320*1000,
		/* idleFlowExpireMillis */	60*1000,
		/* publicIp */ 			publicIp,
		/* macPortTimeoutMillis */	40*1000,
		/* ovsdb */			ovsdb);
	controller.setControllerStub(new MockControllerStub());
    }
}
