/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.ControllerBuilder;
import com.midokura.midolman.openvswitch.ControllerConnectionMode;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.openflow.AgFlowStats;
import com.midokura.midonet.smoketest.openflow.FlowStats;
import com.midokura.midonet.smoketest.openflow.PortStats;
import com.midokura.midonet.smoketest.openflow.ServiceController;
import com.midokura.midonet.smoketest.openflow.TableStats;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;

public class StatsTest {
    static Tenant tenant1;
    static TapPort tapPort1;
    static TapPort tapPort2;
    static InternalPort peerPort;
    static IntIPv4 tapIp1;
    static IntIPv4 tapIp2;
    static IntIPv4 rtrIp;
    static IntIPv4 peerIp;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static OpenvSwitchDatabaseConnection ovsdb;
    static MidolmanMgmt mgmt;
    static ServiceController svcController;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        // First clean up left-overs from previous incomplete tests.
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        Random rand = new Random(System.currentTimeMillis());
        String tenantName = "tenant" + rand.nextInt();
        tenant1 = new Tenant.Builder(mgmt).setName(tenantName).build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        rtrIp = IntIPv4.fromString("192.168.100.1");
        tapIp1 = IntIPv4.fromString("192.168.100.11");
        tapIp2 = IntIPv4.fromString("192.168.100.12");
        peerIp = IntIPv4.fromString("192.168.100.13");
        tapPort1 = router1.addPort(ovsdb).setDestination(tapIp1.toString())
                .buildTap();
        helper1 = new PacketHelper(tapPort1.getInnerMAC(), tapIp1,
                tapPort1.getOuterMAC(), rtrIp);

        tapPort2 = router1.addPort(ovsdb).setDestination(tapIp2.toString())
                .setOVSPortName("tapPort2").buildTap();
        helper2 = new PacketHelper(tapPort2.getInnerMAC(), tapIp2,
                tapPort2.getOuterMAC(), rtrIp);

        peerPort = router1.addPort(ovsdb).setDestination(peerIp.toString())
                .buildInternal();

        ControllerBuilder ctlBuilder = ovsdb.addBridgeOpenflowController(
                "smoke-br", "ptcp:6640");
        ctlBuilder.connectionMode(ControllerConnectionMode.OUT_OF_BAND);
        ctlBuilder.build();
        svcController = new ServiceController(6640);
        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");
        tapPort1.remove();
        tapPort2.remove();
        tenant1.delete();
    }

    @Test
    public void test() {
        byte[] request;

        // Port stats for tapPort1
        PortStats pStat1 = svcController.getPortStats(tapPort1.getPortNum());
        // No packets received/transmitted/etc.
        pStat1.expectRx(0).expectTx(0).expectRxDrop(0).expectTxDrop(0);
        // Arp for router's mac.
        assertTrue(tapPort1.send(helper1.makeArpRequest()));
        helper1.checkArpReply(tapPort1.recv());
        // Check updated port stats
        pStat1.refresh().expectRx(1).expectTx(1).expectRxDrop(0)
                .expectTxDrop(0);

        // Aggregate stats for flows whose destination ip is tap1.
        AgFlowStats flowsTo1 = svcController.getAgFlowStats(new MidoMatch()
                .setNetworkDestination(tapIp1.address, 32));
        flowsTo1.expectFlowCount(0);

        // Individual flows from tap1 to peerPort and back.
        MidoMatch match1ToPeer = new MidoMatch().setNetworkSource(
                tapIp1.address, 32).setNetworkDestination(peerIp.address, 32);
        List<FlowStats> fstats = svcController.getFlowStats(match1ToPeer);
        Assert.assertEquals(0, fstats.size());
        MidoMatch matchPeerTo1 = new MidoMatch().setNetworkSource(
                peerIp.address, 32).setNetworkDestination(tapIp1.address, 32);
        fstats = svcController.getFlowStats(matchPeerTo1);
        Assert.assertEquals(0, fstats.size());

        // Send ICMP echo requests from tap1 to peer port. Peer is an interal
        // port so the host OS will reply.
        for (int i = 0; i < 5; i++) {
            request = helper1.makeIcmpEchoRequest(peerIp);
            assertTrue(tapPort1.send(request));
            if (0 == i) {
                // The router ARPs before delivering the reply packet.
                helper1.checkArpRequest(tapPort1.recv());
                assertTrue(tapPort1.send(helper1.makeArpReply()));
            }
            PacketHelper.checkIcmpEchoReply(request, tapPort1.recv());
        }
        pStat1.refresh().expectRx(7).expectTx(7).expectRxDrop(0)
                .expectTxDrop(0);
        fstats = svcController.getFlowStats(match1ToPeer);
        Assert.assertEquals(1, fstats.size());
        FlowStats flow1toPeer = fstats.get(0);
        flow1toPeer.expectCount(5);
        fstats = svcController.getFlowStats(matchPeerTo1);
        Assert.assertEquals(1, fstats.size());
        FlowStats flowPeerTo1 = fstats.get(0);
        flowPeerTo1.expectCount(5);

        // Now send ICMP echo requests from tap2 to tap1. Don't answer them.
        PortStats pStat2 = svcController.getPortStats(tapPort2.getPortNum());
        pStat1.expectRx(0).expectTx(0).expectRxDrop(0).expectTxDrop(0);
        // Ping internal port from tap1.
        for (int i = 0; i < 5; i++) {
            request = helper2.makeIcmpEchoRequest(tapIp1);
            assertTrue(tapPort2.send(request));
            // Check that tap1 receives what was sent by tap2.
            helper1.checkIcmpEchoRequest(request, tapPort1.recv());
        }
        pStat2.refresh().expectRx(5).expectTx(0).expectRxDrop(0)
                .expectTxDrop(0);
        pStat1.refresh().expectRx(7).expectTx(12).expectRxDrop(0)
                .expectTxDrop(0);
        // No change to the flows between tap1 and peerPort.
        flow1toPeer.findSameInList(svcController.getFlowStats(match1ToPeer))
                .expectCount(5);
        flowPeerTo1.findSameInList(svcController.getFlowStats(matchPeerTo1))
                .expectCount(5);
        // The aggregate flow stats to tap1 have changed.
        flowsTo1.refresh().expectFlowCount(2).expectPktCount(10);

        // Get table statistics.
        List<TableStats> tableStats = svcController.getTableStats();
        Assert.assertEquals(2, tableStats);
        TableStats tStats = tableStats.get(0);
        tStats.expectActive(4).expectLookups(10).expectMatches(6);
    }
}
