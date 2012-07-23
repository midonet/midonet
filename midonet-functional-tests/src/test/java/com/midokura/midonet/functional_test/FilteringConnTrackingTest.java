/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.*;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;


public class FilteringConnTrackingTest {
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    Tenant tenant1;

    Bridge bridge1;
    BridgePort bPort1;
    BridgePort bPort2;
    BridgePort bPort3;
    BridgePort bPort4;
    BridgePort bPort5;
    LogicalBridgePort bPort6;
    LogicalRouterPort rPort1;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;
    TapWrapper tap4;
    TapWrapper tap5;
    OvsBridge ovsBridge1;

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
    public void setUp() throws IOException, InterruptedException {
        OpenvSwitchDatabaseConnection ovsdb =
                new OpenvSwitchDatabaseConnectionImpl(
                        "Open_vSwitch", "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "FilteringConTrackTest");
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-l2filtering").build();
        bridge1 = tenant1.addBridge().setName("br1").build();
        Router rtr = tenant1.addRouter().setName("rtr1").build();
        // Link the Bridge and Router
        rPort1 = rtr.addLinkPort()
                    .setNetworkAddress("10.0.0.0")
                    .setNetworkLength(24)
                    .setPortAddress("10.0.0.1").build();
        bPort6 = bridge1.addLinkPort().build();
        rPort1.link(bPort6);

        // Add ports to the bridge.
        bPort1 = bridge1.addPort().build();
        bPort2 = bridge1.addPort().build();
        bPort3 = bridge1.addPort().build();
        bPort4 = bridge1.addPort().build();
        bPort5 = bridge1.addPort().build();

        tap1 = new TapWrapper("l2filterTap1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());
        tap2 = new TapWrapper("l2filterTap2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());
        tap3 = new TapWrapper("l2filterTap3");
        ovsBridge1.addSystemPort(bPort3.getId(), tap3.getName());
        tap4 = new TapWrapper("l2filterTap4");
        ovsBridge1.addSystemPort(bPort4.getId(), tap4.getName());
        tap5 = new TapWrapper("l2filterTap5");
        ovsBridge1.addSystemPort(bPort5.getId(), tap5.getName());

        sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
        removeTapWrapper(tap4);
        removeTapWrapper(tap5);

        removeBridge(ovsBridge1);
        stopMidolman(midolman1);

        if (null != rPort1)
            rPort1.unlink();
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    public MAC arpFromTapAndGetReply(
            TapWrapper tap, MAC dlSrc, IntIPv4 ipSrc, IntIPv4 ipDst)
            throws MalformedPacketException {
        assertThat("We failed to send the ARP request.",
                tap.send(PacketHelper.makeArpRequest(dlSrc, ipSrc, ipDst)));
        return PacketHelper.checkArpReply(
                tap.recv(), ipDst, dlSrc, ipSrc);
    }

    public void udpFromTapArrivesAtTap(TapWrapper tapSrc, TapWrapper tapDst,
            MAC dlSrc, MAC dlDst, IntIPv4 ipSrc, IntIPv4 ipDst,
            short tpSrc, short tpDst, byte[] payload) {
        byte[] pkt = PacketHelper.makeUDPPacket(
                dlSrc, ipSrc, dlDst, ipDst, tpSrc, tpDst, payload);
        assertThat("The packet should have been sent from the source tap.",
                tapSrc.send(pkt));
        assertThat("The packet should have arrived at the destination tap.",
                tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
    }

    public void udpFromTapDoesntArriveAtTap(TapWrapper tapSrc, TapWrapper tapDst,
            MAC dlSrc, MAC dlDst, IntIPv4 ipSrc, IntIPv4 ipDst,
            short tpSrc, short tpDst, byte[] payload) {
        byte[] pkt = PacketHelper.makeUDPPacket(
                dlSrc, ipSrc, dlDst, ipDst, tpSrc, tpDst, payload);
        assertThat("The packet should have been sent from the source tap.",
                tapSrc.send(pkt));
        assertThat("The packet should not have arrived at the destination tap.",
                tapDst.recv(), nullValue());
    }

    @Test
    public void test() throws MalformedPacketException, InterruptedException {
        MAC mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        MAC mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        MAC mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        MAC mac4 = MAC.fromString("02:aa:bb:cc:dd:d4");
        MAC mac5 = MAC.fromString("02:aa:bb:cc:dd:d5");
        IntIPv4 rtrIp = IntIPv4.fromString("10.0.0.1");
        IntIPv4 ip1 = IntIPv4.fromString("10.0.0.2");
        IntIPv4 ip2 = IntIPv4.fromString("10.0.0.3");
        IntIPv4 ip3 = IntIPv4.fromString("10.0.0.4");
        IntIPv4 ip4 = IntIPv4.fromString("10.0.0.5");
        IntIPv4 ip5 = IntIPv4.fromString("10.0.0.6");
        final short port1 = 1;
        final short port2 = 2;
        final short port3 = 3;
        final short port4 = 4;
        final short port5 = 5;

        // Send ARPs from each edge port so that the bridge can learn MACs.
        MAC rtrMac = arpFromTapAndGetReply(tap1, mac1, ip1, rtrIp);
        assertThat("The router's IP should always resolve to the same MAC",
                rtrMac, equalTo(arpFromTapAndGetReply(tap2, mac2, ip2, rtrIp)));
        assertThat("The router's IP should always resolve to the same MAC",
                rtrMac, equalTo(arpFromTapAndGetReply(tap3, mac3, ip3, rtrIp)));
        assertThat("The router's IP should always resolve to the same MAC",
                rtrMac, equalTo(arpFromTapAndGetReply(tap4, mac4, ip4, rtrIp)));
        assertThat("The router's IP should always resolve to the same MAC",
                rtrMac, equalTo(arpFromTapAndGetReply(tap5, mac5, ip5, rtrIp)));

        // tap3 (ip3, mac3) can send packets to (ip1, mac1) and (ip2, mac2).
        udpFromTapArrivesAtTap(tap3, tap1, mac3, mac1, ip3, ip1, port3, port1,
                               "three => one".getBytes());
        udpFromTapArrivesAtTap(tap3, tap2, mac3, mac2, ip3, ip2, port3, port2,
                               "three => two".getBytes());

        // Now create a chain for the L2 virtual bridge's inbound filter.
        RuleChain brInFilter = tenant1.addChain().setName("brInFilter").build();
        // Add a rule that accepts all return packets.
        Rule rule0 = brInFilter.addRule().setPosition(1).matchReturnFlow()
                               .setSimpleType(DtoRule.Accept).build();
        // Add a rule that drops packets from ip4 to ip1. Because of the
        // previous rule, return packets from ip4 to ip1 will still pass.
        Rule rule1 = brInFilter.addRule().setPosition(2)
                               .matchNwSrc(ip4, 32).matchNwDst(ip1, 32)
                               .setSimpleType(DtoRule.Drop).build();
        // Add a rule that drops packets from mac5 to mac2. Because of the
        // initial conn-tracking rule, return pkts from mac5 to mac2 still pass.
        Rule rule2 = brInFilter.addRule().setPosition(3)
                               .matchDlSrc(mac5).matchDlDst(mac2)
                               .setSimpleType(DtoRule.Drop).build();
        // Set this chain as the bridge's inbound filter.
        bridge1.setInboundFilter(brInFilter.chain.getId());

        sleepBecause("we need the network to process the new filter", 2);

        // ip4 cannot send packets to ip1
        udpFromTapDoesntArriveAtTap(tap4, tap1, mac4, mac1, ip4, ip1, port4,
                port1, "four => one".getBytes());
        udpFromTapDoesntArriveAtTap(tap4, tap2, mac4, mac2, ip4, ip1, port4,
                port2, "four => two (except IP#=1)".getBytes());

        // mac5 cannot send packets to mac2
        udpFromTapDoesntArriveAtTap(tap5, tap2, mac5, mac2, ip5, ip2, port5,
                port2, "five => two".getBytes());
        udpFromTapDoesntArriveAtTap(tap5, tap2, mac5, mac2, ip4, ip2, port4,
                port2, "four (except MAC=5) => two".getBytes());

        // Now send forward packets to set up the connection tracking based
        // peepholes in the filter.
        udpFromTapArrivesAtTap(tap1, tap4, mac1, mac4, ip1, ip4, port1, port2,
                "one => four (except port#=2) forward".getBytes());
        udpFromTapArrivesAtTap(tap2, tap5, mac2, mac5, ip2, ip5, port2, port3,
                "two => five (except port#=3) forward".getBytes());

        // Verify the peepholes work.
        udpFromTapArrivesAtTap(tap4, tap1, mac4, mac1, ip4, ip1, port2, port1,
                "four (except port#=2) => one return".getBytes());
        udpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip5, ip2, port3, port2,
                "five (except port#=3) => two return".getBytes());

        // TODO(jlm): Verify the return flow peepholes work on port filters.
    }
}
