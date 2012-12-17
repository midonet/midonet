/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;

import com.midokura.midolman.flows.WildcardMatch;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.ExteriorRouterPort;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.ICMP;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.util.Waiters.sleepBecause;

@Ignore
public class DeletePortTest {

    private final static Logger log = LoggerFactory.getLogger(
        DeletePortTest.class);
    public static final String INT_PORT_NAME = "intDeletePort";

    IntIPv4 rtrIp = IntIPv4.fromString("192.168.231.1");
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
    IntIPv4 ip3 = IntIPv4.fromString("192.168.231.4");

    Router rtr;
    Tenant tenant1;
    ExteriorRouterPort p1;
    ExteriorRouterPort p2;
    ExteriorRouterPort p3;
    TapWrapper tap1;
    TapWrapper tap2;
    PacketHelper helper1;
    PacketHelper helper2;
    MidolmanMgmt api;
    MidolmanLauncher midolman;

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
        api = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start("DeletePortTest");

        log.debug("Building tenant");
        tenant1 = new Tenant.Builder(api).setName("tenant-port-delete").build();
        rtr = tenant1.addRouter().setName("rtr1").build();
        log.debug("Done building tenant");

        p1 = rtr.addVmPort().setVMAddress(ip1).build();
        tap1 = new TapWrapper("tapDelPort1");
        //ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());

        p2 = rtr.addVmPort().setVMAddress(ip2).build();
        tap2 = new TapWrapper("tapDelPort2");
        //ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());

        p3 = rtr.addVmPort().setVMAddress(ip3).build();
        //ovsBridge.addInternalPort(p3.port.getId(), INT_PORT_NAME, ip3, 24);

        helper1 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:01"), ip1, rtrIp);
        helper2 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:02"), ip2, rtrIp);

        sleepBecause("the network config should boot up", 5);

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                   tap1.send(helper1.makeArpRequest()));

        MAC rtrMac = helper1.checkArpReply(tap1.recv());
        helper1.setGwMac(rtrMac);

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                   tap2.send(helper2.makeArpRequest()));

        rtrMac = helper2.checkArpReply(tap2.recv());
        helper2.setGwMac(rtrMac);
    }



    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        stopMidolman(midolman);
        removeTenant(tenant1);
      //  stopMidolmanMgmt(api);
    }

    @Test
    public void testPortDelete()
        throws InterruptedException, MalformedPacketException {
        short num1 = 0;
            //ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        short num2 = 0;//ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        short num3 = 0;//ovsdb.getPortNumByUUID(ovsdb.getPortUUID(INT_PORT_NAME));
        log.debug(
            "The OF ports have numbers: {}, {}, and {}",
            new Object[]{num1, num2, num3});

        // Remove/re-add the two tap ports to remove all flows.
        //ovsBridge.deletePort(tap1.getName());
        //ovsBridge.deletePort(tap2.getName());
        // Sleep to let OVS complete the port requests.
        sleepBecause("wait OVS to complete the port deletions", 1);

        //ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());
        //ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());
        // Sleep to let OVS complete the port requests.
        sleepBecause("wait OVS to complete the port creations", 1);

        //num1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        //num2 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        //num3 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(INT_PORT_NAME));
        log.debug("The OF ports have numbers: {}, {}, and {}",
                  new Object[]{num1, num2, num3});

        // Verify that there are no ICMP flows.
        WildcardMatch icmpMatch = new WildcardMatch()
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        //List<FlowStats> fstats = null; //svcController.getFlowStats
        // (icmpMatch);
        //assertThat("There are no stats for ICMP yet",
        //           fstats, hasSize(0));

        // Send ICMPs from p1 to internal port p3.
        byte[] ping1_3 = helper1.makeIcmpEchoRequest(ip3);

        assertThat("The tap should have sent the packet",
                   tap1.send(ping1_3));

        // Note: the virtual router ARPs before delivering the reply packet.
        helper1.checkArpRequest(tap1.recv());
        assertThat("The tap should have sent the ARP reply",
                   tap1.send(helper1.makeArpReply()));

        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping1_3, tap1.recv());

        // Send ICMPs from p2 to internal port p3.
        byte[] ping2_3 = helper2.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet", tap2.send(ping2_3));

        // Note: the virtual router ARPs before delivering the reply packet.
        helper2.checkArpRequest(tap2.recv());
        assertThat("The tap should have sent the ARP reply",
                   tap2.send(helper2.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping2_3, tap2.recv());

        // Sleep to let OVS update its stats.
        sleepBecause("OVS needs to update it's stats", 1);

        // Now there should be 4 ICMP flows
        //fstats = svcController.getFlowStats(icmpMatch);
        //assertEquals(4, fstats.size());

        // Verify that there are flows: 1->3, 3->1, 2->3, 3->2.
        /*fstats =
            svcController.getFlowStats(
                new MidoMatch().setNetworkSource(ip1.addressAsInt())
                               .setNetworkDestination(ip3.addressAsInt()));*/

        //assertThat("Only one FlowStats object should be visible",
        //           fstats, hasSize(1));

        //FlowStats flow1_3 = null; //fstats.get(0);
        //flow1_3.expectCount(1).expectOutputAction(num3);

        /*fstats = svcController.getFlowStats(
            new MidoMatch().setNetworkSource(ip3.addressAsInt())
                           .setNetworkDestination(ip1.addressAsInt()));*/
        //assertThat("Only one FlowStats object should be visible.",
        //           fstats, hasSize(1));
        //FlowStats flow3_1 = fstats.get(0);
        //flow3_1.expectCount(1).expectOutputAction(num1);

        /*fstats = svcController.getFlowStats(
            new MidoMatch().setNetworkSource(ip2.addressAsInt())
                           .setNetworkDestination(ip3.addressAsInt()));
        assertThat("One one FlowStats object should be visible.",
                   fstats, hasSize(1));
        FlowStats flow2_3 = fstats.get(0);
        flow2_3.expectCount(1).expectOutputAction(num3);*/

        /*fstats = svcController.getFlowStats(
            new MidoMatch().setNetworkSource(ip3.addressAsInt())
                           .setNetworkDestination(ip2.addressAsInt()));
        assertThat("Only one FlowStats object should be visible.",
                   fstats, hasSize(1));
        FlowStats flow3_2 = fstats.get(0);
        flow3_2.expectCount(1).expectOutputAction(num2);*/

        // Repeat the pings and verify that the packet counts increase.
        assertPacketWasSentOnTap(tap1, ping1_3);
        assertPacketWasSentOnTap(tap2, ping2_3);

        // Sleep to let OVS update its stats.
        sleepBecause("OVS needs to update its stats", 1);

        // Verify that the flow counts have increased as expected.
        // TODO(pino): re-enable the following checks after committing NXM changes.
        /*
        flow1_3.findSameInList(svcController.getFlowStats(flow1_3.getMatch()))
               .expectCount(2).expectOutputAction(num3);
        flow3_1.findSameInList(svcController.getFlowStats(flow3_1.getMatch()))
               .expectCount(2).expectOutputAction(num1);
        flow2_3.findSameInList(svcController.getFlowStats(flow2_3.getMatch()))
               .expectCount(2).expectOutputAction(num3);
        flow3_2.findSameInList(svcController.getFlowStats(flow3_2.getMatch()))
               .expectCount(2).expectOutputAction(num2);
        */

        // Now remove p3 and verify that all flows are removed since they
        // are ingress or egress at p3.
        //ovsBridge.deletePort(INT_PORT_NAME);
        sleepBecause("OVS should process the internal port deletion", 2);

        //fstats = svcController.getFlowStats(icmpMatch);
        //assertThat("The list of FlowStats should be empty", fstats,
        //    hasSize(0));
    }
}
