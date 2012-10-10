/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.sdn.flows.WildcardMatch;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.util.Waiters.sleepBecause;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@Ignore
public class BridgePortDeleteTest {

    private final static Logger log =
        LoggerFactory.getLogger(BridgePortDeleteTest.class);

    Tenant tenant1;
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
    IntIPv4 ip3 = IntIPv4.fromString("192.168.231.4");

    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    BridgePort bPort1;
    BridgePort bPort2;
    BridgePort bPort3;
    Bridge bridge1;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;

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

        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "BridgePortDeleteTest-smoke_br");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-bridge").build();
        bridge1 = tenant1.addBridge().setName("br1").build();


        bPort1 = bridge1.addPort().build();
        tap1 = new TapWrapper("tapBridgeDel1");
        //ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());

        bPort2 = bridge1.addPort().build();
        tap2 = new TapWrapper("tapBridgeDel2");
        //ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());

        bPort3 = bridge1.addPort().build();
        tap3 = new TapWrapper("tapBridgeDel3");
        //ovsBridge1.addSystemPort(bPort3.getId(), tap3.getName());

        sleepBecause("we want the network config to boot up properly", 5);
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);

        stopMidolman(midolman1);

        removeTenant(tenant1);
       // stopMidolmanMgmt(mgmt);
    }

    private void sendPacket(byte[] pkt, TapWrapper fromTap,
                            TapWrapper[] toTaps) {
        assertThat("The ARP packet was sent properly.", fromTap.send(pkt));
        for (TapWrapper dstTap : toTaps)
            assertThat("The received packet is the same as the one sent",
                    dstTap.recv(), equalTo(pkt));
    }

    @Test
    public void testPortDelete() throws InterruptedException {
        // Use different MAC addrs from other tests (unlearned MACs).
        MAC mac1 = MAC.fromString("02:00:00:00:aa:01");
        MAC mac2 = MAC.fromString("02:00:00:00:aa:02");
        MAC mac3 = MAC.fromString("02:00:00:00:aa:03");

        // Send broadcast from Mac1/port1.
        byte[] pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);
        sendPacket(pkt, tap1, new TapWrapper[] {tap2, tap3});

        // There should now be one flow that outputs to ALL.
        Thread.sleep(1000);
        WildcardMatch match1 = new WildcardMatch().setDataLayerSource(mac1);

        //List<FlowStats> fstats = null; //svcController.getFlowStats(match1);
        //assertThat("We should have only one FlowStats object.",
        //           fstats, hasSize(1));

        short portNum1 = 0;
            //ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        short portNum2 = 0;
            //ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        short portNum3 = 0;
            //ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap3.getName()));
        //FlowStats flow1 = null; //fstats.get(0);
        Set<Short> expectOutputActions = new HashSet<Short>();
        // port 1 is the ingress port, so not output to.
        expectOutputActions.add(portNum2);
        expectOutputActions.add(portNum3);
        //flow1.expectCount(1).expectOutputActions(expectOutputActions);

        // Send unicast from Mac2/port2 to mac1.
        pkt = PacketHelper.makeIcmpEchoRequest(mac2, ip2, mac1, ip1);
        assertThat(
            String.format(
                "The ICMP echo packet was properly sent via %s", tap2.getName()),
            tap2.send(pkt));

        assertThat(
            String.format("We received the same packet on %s", tap1.getName()),
            tap1.recv(), equalTo(pkt));

        // There should now be one flow that outputs to port 1.
        Thread.sleep(1000);
        WildcardMatch match2 = new WildcardMatch().setDataLayerSource(mac2);
        //fstats = svcController.getFlowStats(match2);
        //assertThat("Only one FlowStats object should be returned.",
        //           fstats, hasSize(1));

        //FlowStats flow2 = null; //fstats.get(0);
        //flow2.expectCount(1).expectOutputAction(portNum1);

        // The last packet caused the bridge to learn the mapping Mac2->port2.
        // That also triggered invalidation of flooded flows: flow1.
        //assertThat("There should be no flow match for the ARP.",
        //        svcController.getFlowStats(match1), hasSize(0));

        // Resend the ARP to re-install the flooded flow.
        pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);
        sendPacket(pkt, tap1, new TapWrapper[] {tap2, tap3});
        //flow1.findSameInList(svcController.getFlowStats(match1))
        //        .expectCount(1).expectOutputActions(expectOutputActions);

        // Delete port1. It is the destination of flow2 and
        // the origin of flow1 - so expect both flows to be removed.
        //ovsBridge1.deletePort(tap1.getName());
        sleepBecause("we want midolman to sense the port deletion", 1);

        /*assertThat(
            "No FlowStats object should be visible after we deleted a port",
            svcController.getFlowStats(match1), hasSize(0));
        assertThat(
            "No FlowStats object should be visible after we deleted a port",
            svcController.getFlowStats(match2), hasSize(0));
        */
    }
}
