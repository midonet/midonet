/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.List;
import static java.lang.String.format;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.AgFlowStats;
import com.midokura.midonet.functional_test.openflow.FlowStats;
import com.midokura.midonet.functional_test.openflow.OpenFlowStats;
import com.midokura.midonet.functional_test.openflow.PortStats;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.RouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;

// TODO(pino): re-enable this test after committing NXM
@Ignore
public class StatsTest {

    private final static Logger log = LoggerFactory.getLogger(StatsTest.class);

    static Tenant tenant1;
    static TapWrapper tapPort1;
    static TapWrapper tapPort2;
    static IntIPv4 tapIp1;
    static IntIPv4 tapIp2;
    static IntIPv4 rtrIp;
    static IntIPv4 peerIp;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static OpenvSwitchDatabaseConnectionImpl ovsdb;
    static MidolmanMgmt mgmt;
    static MidolmanLauncher midolman;
    static OpenFlowStats svcController;
    static OvsBridge ovsBridge;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start("StatsTest");

        // First clean up left-overs from previous incomplete tests.
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge = new OvsBridge(ovsdb, "smoke-br");
        ovsBridge.addServiceController(6640);
        Thread.sleep(1000);
        svcController = new ServiceController(6640);

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-stats").build();
        Router router1 = tenant1.addRouter().setName("rtr1").build();

        rtrIp = IntIPv4.fromString("192.168.231.1");
        tapIp1 = IntIPv4.fromString("192.168.231.11");
        tapIp2 = IntIPv4.fromString("192.168.231.12");
        peerIp = IntIPv4.fromString("192.168.231.13");

        RouterPort p1 = router1.addVmPort().setVMAddress(tapIp1).build();
        tapPort1 = new TapWrapper("statsTestTap1");
        ovsBridge.addSystemPort(p1.port.getId(), tapPort1.getName());
        helper1 = new PacketHelper(
                MAC.fromString("02:00:bb:bb:00:01"), tapIp1, rtrIp);

        RouterPort p2 = router1.addVmPort().setVMAddress(tapIp2).build();
        tapPort2 = new TapWrapper("statsTestTap2");
        ovsBridge.addSystemPort(p2.port.getId(), tapPort2.getName());
        helper2 = new PacketHelper(
                MAC.fromString("02:00:bb:bb:00:02"), tapIp2, rtrIp);

        RouterPort p3 = router1.addVmPort().setVMAddress(peerIp).build();
        ovsBridge.addInternalPort(p3.port.getId(), "statsTestInt", peerIp, 24);

        sleepBecause("we want to wait for the network config to settle", 5);
    }

    @AfterClass
    public static void tearDown() throws InterruptedException {
        removeTapWrapper(tapPort1);
        removeTapWrapper(tapPort2);
        stopMidolman(midolman);
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
        removeBridge(ovsBridge);
    }

    @Test
    public void testStatsAggregation()
            throws InterruptedException, MalformedPacketException {
        byte[] request;
        short portNum1 =
            ovsdb.getPortNumByUUID(
                ovsdb.getPortUUID(tapPort1.getName()));
        short portNum2 =
            ovsdb.getPortNumByUUID(
                ovsdb.getPortUUID(tapPort2.getName()));

        // Port stats for tapPort1
        PortStats statsForPort1 = svcController.getPortStats(portNum1);
        // No packets received/transmitted/etc.
        statsForPort1.expectRx(0).expectTx(0)
                     .expectRxDrop(0).expectTxDrop(0);

        // Arp for router's mac.
        assertThat("The ARP request package was sent via tapPort1",
                   tapPort1.send(helper1.makeArpRequest()), equalTo(true));
        helper1.checkArpReply(tapPort1.recv());

        // Check updated port stats
        statsForPort1.refresh()
                     .expectRx(1).expectTx(1)
                     .expectRxDrop(0).expectTxDrop(0);

        // Aggregate stats for flows whose destination ip is tap1.
        AgFlowStats flowsTo1 =
            svcController.getAgFlowStats(
                new MidoMatch().setNetworkDestination(tapIp1.address, 32));
        flowsTo1.expectFlowCount(0);

        // Individual flows from tap1 to peerPort and back.
        MidoMatch match1ToPeer =
            new MidoMatch()
                .setNetworkSource(tapIp1.address, 32)
                .setNetworkDestination(peerIp.address, 32);

        List<FlowStats> fstats = svcController.getFlowStats(match1ToPeer);
        assertThat("No FlowStats are available from tap to peer port",
                   fstats, hasSize(0));

        MidoMatch matchPeerTo1 =
            new MidoMatch()
                .setNetworkSource(peerIp.address, 32)
                .setNetworkDestination(tapIp1.address, 32);
        fstats = svcController.getFlowStats(matchPeerTo1);
        assertThat("No FlowStats are available from peer to tap port",
                   fstats, hasSize(0));

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
        sleepBecause("wait for OVS to update stats", 1);
        statsForPort1.refresh()
                     .expectRx(7).expectTx(7)
                     .expectRxDrop(0).expectTxDrop(0);

        fstats = svcController.getFlowStats(match1ToPeer);
        assertThat("One FlowStat is available from match to peer port",
                   fstats, hasSize(1));
        FlowStats statsFlow1ToPeer = fstats.get(0);
        statsFlow1ToPeer.expectCount(5);

        fstats = svcController.getFlowStats(matchPeerTo1);
        assertThat("One FlowStat is available from peer port to match",
                   fstats, hasSize(1));
        FlowStats statsFlowPeerTo1 = fstats.get(0);
        statsFlowPeerTo1.expectCount(5);

        // Now send ICMP echo requests from tap2 to tap1. Don't answer them.
        PortStats statsForPort2 = svcController.getPortStats(portNum2);
        statsForPort2
            .expectRx(0).expectTx(0)
            .expectRxDrop(0).expectTxDrop(0);

        // Ping internal port from tap1.
        for (int i = 0; i < 5; i++) {
            request = helper2.makeIcmpEchoRequest(tapIp1);
            assertTrue(tapPort2.send(request));
            // Check that tap1 receives what was sent by tap2.
            helper1.checkIcmpEchoRequest(request, tapPort1.recv());
        }
        // Sleep to give OVS a chance to update its stats.
        sleepBecause("wait for OVS to update it's stats", 1);
        statsForPort2.refresh()
                     .expectRx(5).expectTx(0)
                     .expectRxDrop(0).expectTxDrop(0);

        statsForPort1.refresh()
                     .expectRx(7).expectTx(12)
                     .expectRxDrop(0).expectTxDrop(0);

        // No change to the flows between tap1 and peerPort.
        statsFlow1ToPeer
            .findSameInList(svcController.getFlowStats(match1ToPeer))
            .expectCount(5);

        statsFlowPeerTo1
            .findSameInList(svcController.getFlowStats(matchPeerTo1))
            .expectCount(5);
        // There's a new flow from tap 2 to 1.
        fstats = svcController.getFlowStats(
            new MidoMatch()
                .setNetworkSource(tapIp2.address, 32)
                .setNetworkDestination(tapIp1.address, 32));
        assertThat(
            format("There is only one stats object from %s to %s",
                   tapIp2.toString(), tapIp1.toString()),
            fstats, hasSize(1));
        FlowStats statsFlowTwoToOne = fstats.get(0);
        statsFlowTwoToOne.expectCount(5);
        // The aggregate flow stats to tap1 now include counts for PeerTo1 And
        // 2To1.
        flowsTo1.refresh().expectFlowCount(2).expectPktCount(10);

        // Get table statistics.

        // TODO: finish this test .. in OVS 1.2 there was only a stats table.
        // In OVS 1.4 there are 256
/*
        List<TableStats> tableStats = svcController.getTableStats();
        assertThat("Table stats size is proper", tableStats, hasSize(256));
        TableStats tStats = tableStats.get(0);
*/

        // Expect: 1) 6 flows (1->Peer, Peer->1, 2->1, +3 per-port DHCP flows);
        // 2) 19 lookups (ARP request+reply from both tap1 and
        // intPort1 + 5 ICMPs from each of 1->Peer, Peer->1 and 2->1);
        // 3) 12 matches (all ICMPs matched except first in each flow).
        // TODO, see Redmine #589 and put active, lookups and matches back
        // tStats.expectActive(6).expectLookups(19).expectMatches(12);
    }
}
