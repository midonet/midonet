/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import static java.lang.String.format;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.waitForBridgeToConnect;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Without_Bgp;

public class BridgeTest {

    private final static Logger log = LoggerFactory.getLogger(BridgeTest.class);

    Tenant tenant1;
    IntIPv4 ip1;
    IntIPv4 ip2;
    IntIPv4 ip3;
    PacketHelper helper1_3;
    PacketHelper helper3_1;
    OpenvSwitchDatabaseConnectionImpl ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    MidolmanLauncher midolman2;
    BridgePort bPort1;
    BridgePort bPort2;
    BridgePort bPort3;
    Bridge bridge1;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;
    OvsBridge ovsBridge1;
    OvsBridge ovsBridge2;
    ServiceController svcController;

    @Before
    public void setUp() throws Exception {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "BridgeTest-smoke_br");
        midolman2 = MidolmanLauncher.start(Without_Bgp, "BridgeTest-smoke_br2");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-bridge").build();
        bridge1 = tenant1.addBridge().setName("br1").build();

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br", bridge1.getId());
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", bridge1.getId(),
                                   "tcp:127.0.0.1:6657");

        // Add a service controller to OVS bridge 1.
        ovsBridge1.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        ip1 = IntIPv4.fromString("192.168.231.2");
        bPort1 = bridge1.addPort();
        tap1 = new TapWrapper("tapBridge1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());

        ip2 = IntIPv4.fromString("192.168.231.3");
        bPort2 = bridge1.addPort();
        tap2 = new TapWrapper("tapBridge2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());

        ip3 = IntIPv4.fromString("192.168.231.4");
        bPort3 = bridge1.addPort();
        tap3 = new TapWrapper("tapBridge3");
        ovsBridge2.addSystemPort(bPort3.getId(), tap3.getName());

        helper1_3 = new PacketHelper(tap1.getHwAddr(), ip1, tap3.getHwAddr(),
                                     ip3);
        helper3_1 = new PacketHelper(tap3.getHwAddr(), ip3, tap1.getHwAddr(),
                                     ip1);

        sleepBecause("we need the network to boot up", 5);
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);

        removeBridge(ovsBridge1);
        removeBridge(ovsBridge2);

        stopMidolman(midolman1);
        stopMidolman(midolman2);

        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    @Test
    public void testPingOverBridge() {
        byte[] sent;

        sent = helper1_3.makeIcmpEchoRequest(ip3);
        assertPacketWasSentOnTap(tap1, sent);

        // Receive the icmp, Mac3 hasn't been learnt so the icmp will be
        // delivered to all the ports.
        helper3_1.checkIcmpEchoRequest(sent, tap3.recv());
        helper3_1.checkIcmpEchoRequest(sent, tap2.recv());

        sent = helper3_1.makeIcmpEchoRequest(ip1);
        assertPacketWasSentOnTap(tap3, sent);
        // Mac1 was learnt, so the message will be sent only to tap1
        helper1_3.checkIcmpEchoRequest(sent, tap1.recv());

        // VM moves, sending from Mac3 Ip3 using tap2
        sent = helper3_1.makeIcmpEchoRequest(ip1);
        assertPacketWasSentOnTap(tap2, sent);
        helper1_3.checkIcmpEchoRequest(sent, tap1.recv());

        sent = helper1_3.makeIcmpEchoRequest(ip3);
        assertPacketWasSentOnTap(tap1, sent);
        helper3_1.checkIcmpEchoRequest(sent, tap2.recv());

        assertNoMorePacketsOnTap(tap3);
        assertNoMorePacketsOnTap(tap1);
    }
}

