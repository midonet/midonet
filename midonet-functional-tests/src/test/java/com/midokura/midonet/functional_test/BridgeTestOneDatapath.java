/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.waitForBridgeToConnect;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;


public class BridgeTestOneDatapath {

    private final static Logger log = LoggerFactory.getLogger(BridgeTestOneDatapath.class);

    Tenant tenant1;
    IntIPv4 ip1, ip2, ip3;
    MAC mac1, mac2, mac3;
    PacketHelper helper1_2;
    PacketHelper helper2_1;
    PacketHelper helper1_3;
    PacketHelper helper3_1;
    OpenvSwitchDatabaseConnectionImpl ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    MidolmanLauncher midolman2;
    BridgePort bPort1, bPort2, bPort3;
    Bridge bridge1;
    TapWrapper tap1, tap2, tap3;
    OvsBridge ovsBridge1;
    ServiceController svcController;

    static LockHelper.Lock lock;

    @BeforeClass
    public static void checkLock() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);
    }

    @AfterClass
    public static void releaseLock() {
        lock.release();
    }

    @Before
    public void setUp() throws Exception {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default,
                                           "BridgeTestOneDatapath-smoke_br");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-bridge").build();
        bridge1 = tenant1.addBridge().setName("br1").build();

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");

        // Add a service controller to OVS bridge 1.
        ovsBridge1.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        // Use IP addresses from the testing range 198.18.0.0/15.
        ip1 = IntIPv4.fromString("198.18.231.2");
        mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        bPort1 = bridge1.addPort().build();
        tap1 = new TapWrapper("tapBridge1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());

        ip2 = IntIPv4.fromString("198.18.231.3");
        mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        bPort2 = bridge1.addPort().build();
        tap2 = new TapWrapper("tapBridge2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());

        ip3 = IntIPv4.fromString("198.18.231.4");
        mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        bPort3 = bridge1.addPort().build();
        tap3 = new TapWrapper("tapBridge3");
        ovsBridge1.addSystemPort(bPort3.getId(), tap3.getName());

        helper1_2 = new PacketHelper(mac1, ip1, mac2, ip2);
        helper2_1 = new PacketHelper(mac2, ip2, mac1, ip1);
        helper1_3 = new PacketHelper(mac1, ip1, mac3, ip3);
        helper3_1 = new PacketHelper(mac3, ip3, mac1, ip1);

        sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);

        removeBridge(ovsBridge1);

        stopMidolman(midolman1);

        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    @Test
    public void testPing() {
        byte[] pkt1to2 = helper1_2.makeIcmpEchoRequest(ip2);
        byte[] pkt2to1 = helper2_1.makeIcmpEchoRequest(ip1);

        // First send pkt1to2 from tap1 so mac1 is learned.
        assertPacketWasSentOnTap(tap1, pkt1to2);

        // Since mac2 hasn't been learnt, the packet will be
        // delivered to all the ports except the one that sent it.
        assertArrayEquals(pkt1to2, tap3.recv());
        assertArrayEquals(pkt1to2, tap2.recv());
        assertNull("The packet should not be flooded to the inPort.",
                tap1.recv());

        // Now send pkt2to1 from tap2.
        assertPacketWasSentOnTap(tap2, pkt2to1);
        // Mac1 was learnt, so the message will be sent only to tap1.
        assertArrayEquals(pkt2to1, tap1.recv());
        assertNull(tap2.recv());
        assertNull(tap3.recv());
        // Now re-send pkt1to2 from tap1 - it should arrive only at tap2
        assertPacketWasSentOnTap(tap1, pkt1to2);
        assertArrayEquals(pkt1to2, tap2.recv());
        assertNull(tap3.recv());
        assertNull(tap1.recv());

        // Simulate mac2 moving to tap3 by sending pkt2to1 from there.
        assertPacketWasSentOnTap(tap3, pkt2to1);
        assertArrayEquals("The packet should still arrive only at tap1.",
                pkt2to1, tap1.recv());
        assertNull(tap2.recv());
        assertNull(tap3.recv());

        // Now if we send pkt1to2 from tap1, it's forwarded only to tap3.
        assertPacketWasSentOnTap(tap1, pkt1to2);
        assertArrayEquals("The packet should arrive only at tap3.",
                pkt1to2, tap3.recv());
        assertNull(tap2.recv());
        assertNull(tap1.recv());
    }
}

