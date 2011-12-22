/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.ControllerBuilder;
import com.midokura.midolman.openvswitch.ControllerConnectionMode;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.openflow.AgFlowStats;
import com.midokura.midonet.smoketest.openflow.FlowStats;
import com.midokura.midonet.smoketest.openflow.OpenFlowStats;
import com.midokura.midonet.smoketest.openflow.PortStats;
import com.midokura.midonet.smoketest.openflow.ServiceController;
import com.midokura.midonet.smoketest.openflow.TableStats;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatsTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(StatsTest.class);

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
    static OpenvSwitchDatabaseConnectionImpl ovsdb;
    static MidolmanMgmt mgmt;
    static OpenFlowStats svcController;

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

        rtrIp = IntIPv4.fromString("192.168.231.1");
        tapIp1 = IntIPv4.fromString("192.168.231.11");
        tapIp2 = IntIPv4.fromString("192.168.231.12");
        peerIp = IntIPv4.fromString("192.168.231.13");
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
        Thread.sleep(1000);
        svcController = new ServiceController(6640);
        Thread.sleep(5000);
    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");

        removePort(tapPort1);
        removePort(tapPort2);
        removeTenant(tenant1);

        mgmt.stop();

        resetZooKeeperState(log);

        /*
        ovsdb.delPort("tapPort1");
        ovsdb.delPort("tapPort2");
        ovsdb.delPort("intPort1");
        Thread.sleep(2000);
        tapPort1.remove();
        tapPort2.remove();
        ovsdb.delBridge("smoke-br");
        tenant1.delete();
        */
    }

    @Test
    public void test() throws InterruptedException {
        byte[] request;
        short portNum1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(
                tapPort1.getName()));
        short portNum2 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(
                tapPort2.getName()));

        // Port stats for tapPort1
        PortStats pStat1 = svcController.getPortStats(portNum1);
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
        assertEquals(0, fstats.size());
        MidoMatch matchPeerTo1 = new MidoMatch().setNetworkSource(
                peerIp.address, 32).setNetworkDestination(tapIp1.address, 32);
        fstats = svcController.getFlowStats(matchPeerTo1);
        assertEquals(0, fstats.size());

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
        // Sleep to give OVS a chance to update its stats.
        Thread.sleep(1000);
        pStat1.refresh().expectRx(7).expectTx(7).expectRxDrop(0)
                .expectTxDrop(0);
        fstats = svcController.getFlowStats(match1ToPeer);
        assertEquals(1, fstats.size());
        FlowStats flow1toPeer = fstats.get(0);
        flow1toPeer.expectCount(5);
        fstats = svcController.getFlowStats(matchPeerTo1);
        assertEquals(1, fstats.size());
        FlowStats flowPeerTo1 = fstats.get(0);
        flowPeerTo1.expectCount(5);

        // Now send ICMP echo requests from tap2 to tap1. Don't answer them.
        PortStats pStat2 = svcController.getPortStats(portNum2);
        pStat2.expectRx(0).expectTx(0).expectRxDrop(0).expectTxDrop(0);
        // Ping internal port from tap1.
        for (int i = 0; i < 5; i++) {
            request = helper2.makeIcmpEchoRequest(tapIp1);
            assertTrue(tapPort2.send(request));
            // Check that tap1 receives what was sent by tap2.
            helper1.checkIcmpEchoRequest(request, tapPort1.recv());
        }
        // Sleep to give OVS a chance to update its stats.
        Thread.sleep(1000);
        pStat2.refresh().expectRx(5).expectTx(0).expectRxDrop(0)
                .expectTxDrop(0);
        pStat1.refresh().expectRx(7).expectTx(12).expectRxDrop(0)
                .expectTxDrop(0);
        // No change to the flows between tap1 and peerPort.
        flow1toPeer.findSameInList(svcController.getFlowStats(match1ToPeer))
                .expectCount(5);
        flowPeerTo1.findSameInList(svcController.getFlowStats(matchPeerTo1))
                .expectCount(5);
        // There's a new flow from tap 2 to 1.
        fstats = svcController.getFlowStats(new MidoMatch().setNetworkSource(
                tapIp2.address, 32).setNetworkDestination(tapIp1.address, 32));
        assertEquals(1, fstats.size());
        FlowStats flow2to1 = fstats.get(0);
        flow2to1.expectCount(5);
        // The aggregate flow stats to tap1 now include counts for PeerTo1 And 2To1.
        flowsTo1.refresh().expectFlowCount(2).expectPktCount(10);

        // Get table statistics.
        List<TableStats> tableStats = svcController.getTableStats();
        assertEquals(1, tableStats.size());
        TableStats tStats = tableStats.get(0);
        // Expect: 1) 6 flows (1->Peer, Peer->1, 2->1, +3 per-port DHCP flows);
        // 2) 19 lookups (ARP request+reply from both tap1 and
        //    intPort1 + 5 ICMPs from each of 1->Peer, Peer->1 and 2->1);
        // 3) 12 matches (all ICMPs matched except first in each flow).
        tStats.expectActive(6).expectLookups(19).expectMatches(12);
    }
}
