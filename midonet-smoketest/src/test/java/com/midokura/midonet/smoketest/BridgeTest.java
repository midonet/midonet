/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.openflow.FlowStats;
import com.midokura.midonet.smoketest.openflow.ServiceController;
import com.midokura.midonet.smoketest.topology.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openflow.protocol.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BridgeTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(BridgeTest.class);

    static Tenant tenant1;
    static IntIPv4 ip1;
    static IntIPv4 ip2;
    static IntIPv4 ip3;
    static PacketHelper helper1_3;
    static PacketHelper helper3_1;
    static OpenvSwitchDatabaseConnectionImpl ovsdb;
    static MidolmanMgmt mgmt;
    static BridgePort bPort1;
    static BridgePort bPort2;
    static BridgePort bPort3;
    static Bridge bridge1;
    static TapWrapper tap1;
    static TapWrapper tap2;
    static TapWrapper tap3;
    static OvsBridge ovsBridge1;
    static OvsBridge ovsBridge2;
    static ServiceController svcController;
    static Random rand = new Random(System.currentTimeMillis());

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant" + rand.nextInt())
                .build();
        bridge1 = tenant1.addBridge().setName("br1").build();

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br", bridge1.getId());
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", bridge1.getId(),
                "tcp:127.0.0.1:6657");

        ip1 = IntIPv4.fromString("192.168.231.2");
        bPort1 = bridge1.addPort();
        tap1 = new TapWrapper("tap1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());

        ip2 = IntIPv4.fromString("192.168.231.3");
        bPort2 = bridge1.addPort();
        tap2 = new TapWrapper("tap2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());

        ip3 = IntIPv4.fromString("192.168.231.4");
        bPort3 = bridge1.addPort();
        tap3 = new TapWrapper("tap3");
        ovsBridge2.addSystemPort(bPort3.getId(), tap3.getName());

        helper1_3 = new PacketHelper(tap1.getHwAddr(), ip1, tap3.getHwAddr(),
                ip3);
        helper3_1 = new PacketHelper(tap3.getHwAddr(), ip3, tap1.getHwAddr(),
                ip1);

        // Add a service controller to OVS bridge 1.
        ovsBridge1.addServiceController(6640);
        Thread.sleep(1000);
        svcController = new ServiceController(6640);
        Thread.sleep(10 * 1000);
    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");
        ovsdb.delBridge("smoke-br2");

        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
        removeTenant(tenant1);

        mgmt.stop();

        resetZooKeeperState(log);
    }

    @Test
    public void testPingOverBridge() {
        synchronized(mgmt) {
        byte[] sent;

        sent = helper1_3.makeIcmpEchoRequest(ip3);
        assertTrue(tap1.send(sent));

        // Receive the icmp, Mac3 hasn't been learnt so the icmp will be
        // delivered to all the ports.
        helper3_1.checkIcmpEchoRequest(sent, tap3.recv());
        helper3_1.checkIcmpEchoRequest(sent, tap2.recv());

        sent = helper3_1.makeIcmpEchoRequest(ip1);
        assertTrue(tap3.send(sent));
        // Mac1 was learnt, so the message will be sent only to tap1
        helper1_3.checkIcmpEchoRequest(sent, tap1.recv());

        // VM moves, sending from Mac3 Ip3 using tap2
        sent = helper3_1.makeIcmpEchoRequest(ip1);
        assertTrue(tap2.send(sent));
        helper1_3.checkIcmpEchoRequest(sent, tap1.recv());

        sent = helper1_3.makeIcmpEchoRequest(ip3);
        assertTrue(tap1.send(sent));
        helper3_1.checkIcmpEchoRequest(sent, tap2.recv());

        assertNull(tap3.recv());
        assertNull(tap1.recv());
        } // end synchronized(mgmt)
    }

    @Test
    public void testPortDelete() throws InterruptedException {
        synchronized(mgmt) {
        // Use different MAC addrs from other tests (unlearned MACs).
        MAC mac1 = MAC.fromString("02:00:00:00:aa:01");
        MAC mac2 = MAC.fromString("02:00:00:00:aa:02");
        // Send broadcast from Mac1/port1.
        byte[] pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);
        assertTrue(tap1.send(pkt));
        assertArrayEquals(pkt, tap2.recv());
        // assertArrayEquals(pkt, tap3.recv());

        // There should now be one flow that outputs to ALL.
        Thread.sleep(1000);
        MidoMatch match1 = new MidoMatch().setDataLayerSource(mac1);
        List<FlowStats> fstats = svcController.getFlowStats(match1);
        assertEquals(1, fstats.size());
        FlowStats flow1 = fstats.get(0);
        flow1.expectCount(1).expectOutputAction(OFPort.OFPP_ALL.getValue());

        // Send unicast from Mac2/port2 to mac1.
        pkt = PacketHelper.makeIcmpEchoRequest(mac2, ip2, mac1, ip1);
        assertTrue(tap2.send(pkt));
        assertArrayEquals(pkt, tap1.recv());

        // There should now be one flow that outputs to port 1.
        Thread.sleep(1000);
        MidoMatch match2 = new MidoMatch().setDataLayerSource(mac2);
        fstats = svcController.getFlowStats(match2);
        assertEquals(1, fstats.size());
        FlowStats flow2 = fstats.get(0);
        short portNum1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(
                tap1.getName()));
        flow2.expectCount(1).expectOutputAction(portNum1);

        // The first flow should not have changed.
        flow1.findSameInList(svcController.getFlowStats(match1)).expectCount(1);

        // Delete port1. It is the destination of flow2 and
        // the origin of flow1 - so expect both flows to be removed.
        ovsBridge1.deletePort(tap1.getName());
        Thread.sleep(1000);
        assertEquals(0, svcController.getFlowStats(match1).size());
        assertEquals(0, svcController.getFlowStats(match2).size());

        // Re-add the OVS port to leave things as we found them.
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());
        } // end synchronized(mgmt)
    }
}
