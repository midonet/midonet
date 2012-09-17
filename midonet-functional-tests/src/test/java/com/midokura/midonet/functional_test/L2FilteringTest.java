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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midonet.client.dto.DtoRule;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.LLDP;
import com.midokura.packets.LLDPTLV;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.LogicalBridgePort;
import com.midokura.midonet.functional_test.topology.LogicalRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.Rule;
import com.midokura.midonet.functional_test.topology.RuleChain;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;

public class L2FilteringTest {
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
        OpenvSwitchDatabaseConnectionImpl ovsdb =
                new OpenvSwitchDatabaseConnectionImpl(
                        "Open_vSwitch", "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "L2FilteringTest");
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

        if (null != rPort1) {
            rPort1.unlink();
        }
        removeTenant(tenant1);
      //  stopMidolmanMgmt(mgmt);
    }

    public MAC arpFromTapAndGetReply(
            TapWrapper tap, MAC dlSrc, IntIPv4 ipSrc, IntIPv4 ipDst)
            throws MalformedPacketException {
        assertThat("We failed to send the ARP request.",
                tap.send(PacketHelper.makeArpRequest(dlSrc, ipSrc, ipDst)));
        return PacketHelper.checkArpReply(
                tap.recv(), ipDst, dlSrc, ipSrc);
    }

    public void icmpFromTapArrivesAtTap(TapWrapper tapSrc, TapWrapper tapDst,
            MAC dlSrc, MAC dlDst, IntIPv4 ipSrc, IntIPv4 ipDst) {
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
                dlSrc, ipSrc, dlDst, ipDst);
        assertThat("The packet should have been sent from the source tap.",
                tapSrc.send(pkt));
        assertThat("The packet should have arrived at the destination tap.",
                tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
    }

    public void icmpFromTapDoesntArriveAtTap(TapWrapper tapSrc, TapWrapper tapDst,
            MAC dlSrc, MAC dlDst, IntIPv4 ipSrc, IntIPv4 ipDst) {
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
                dlSrc, ipSrc, dlDst, ipDst);
        assertThat("The packet should have been sent from the source tap.",
                tapSrc.send(pkt));
        assertThat("The packet should not have arrived at the destination tap.",
                tapDst.recv(), nullValue());
    }

    public byte[] makeLLDP(MAC dlSrc, MAC dlDst) {
        LLDP packet = new LLDP();
        LLDPTLV chassis = new LLDPTLV();
        chassis.setType((byte)0xca);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType((byte) 0);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType((byte) 40);
        ttl.setLength((short) 3);
        ttl.setValue("ttl".getBytes());
        packet.setChassisId(chassis);
        packet.setPortId(port);
        packet.setTtl(ttl);

        Ethernet frame = new Ethernet();
        frame.setPayload(packet);
        frame.setEtherType(LLDP.ETHERTYPE);
        frame.setDestinationMACAddress(dlDst);
        frame.setSourceMACAddress(dlSrc);
        return frame.serialize();
    }

    public void lldpFromTapArrivesAtTap(
            TapWrapper tapSrc, TapWrapper tapDst, MAC dlSrc, MAC dlDst) {
        byte[] pkt = makeLLDP(dlSrc, dlDst);
        assertThat("The packet should have been sent from the source tap.",
                tapSrc.send(pkt));
        assertThat("The packet should have arrived at the destination tap.",
                tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
    }

    public void lldpFromTapDoesntArriveAtTap(
            TapWrapper tapSrc, TapWrapper tapDst, MAC dlSrc, MAC dlDst) {
        byte[] pkt = makeLLDP(dlSrc, dlDst);
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
        icmpFromTapArrivesAtTap(tap3, tap1, mac3, mac1, ip3, ip1);
        icmpFromTapArrivesAtTap(tap3, tap2, mac3, mac2, ip3, ip2);
        // tap3 (mac3) can send LLDP packets to mac1.
        lldpFromTapArrivesAtTap(tap3, tap1, mac3, mac1);
        // Retry the LLDP.
        lldpFromTapArrivesAtTap(tap3, tap1, mac3, mac1);

        // Now create a chain for the L2 virtual bridge's inbound filter.
        RuleChain brInFilter = tenant1.addChain().setName("brInFilter").build();
        // Add a rule that drops packets from ip4 to ip1.
        Rule rule1 = brInFilter.addRule()
                .matchNwSrc(ip4, 32).matchNwDst(ip1, 32)
                .setSimpleType(DtoRule.Drop).build();
        // Add a rule that drops packets from mac5 to mac2.
        Rule rule2 = brInFilter.addRule()
                .matchDlSrc(mac5).matchDlDst(mac2)
                .setSimpleType(DtoRule.Drop).build();
        // Add a rule that drops LLDP packets.
        Rule rule3 = brInFilter.addRule().matchDlType(LLDP.ETHERTYPE)
                .setSimpleType(DtoRule.Drop).build();
        // Set this chain as the bridge's inbound filter.
        bridge1.setInboundFilter(brInFilter.chain.getId());

        sleepBecause("we need the network to process the new filter", 2);

        // ip4 cannot send packets to ip1
        icmpFromTapDoesntArriveAtTap(tap4, tap1, mac4, mac1, ip4, ip1);
        icmpFromTapDoesntArriveAtTap(tap4, tap2, mac4, mac2, ip4, ip1);

        // mac5 cannot send packets to mac2
        icmpFromTapDoesntArriveAtTap(tap5, tap2, mac5, mac2, ip5, ip2);
        icmpFromTapDoesntArriveAtTap(tap5, tap2, mac5, mac2, ip4, ip2);

        // No one can send LLDP packets.
        lldpFromTapDoesntArriveAtTap(tap3, tap2, mac3, mac2);
        lldpFromTapDoesntArriveAtTap(tap1, tap2, mac1, mac2);

        // Now remove the previous rules.
        rule1.delete();
        rule2.delete();
        rule3.delete();
        // Add a rule the drops any packet from mac1.
        brInFilter.addRule().matchDlSrc(mac1)
                .setSimpleType(DtoRule.Drop).build();
        // add a rule that drops any IP packet that ingreses on tap2.
        brInFilter.addRule().matchInPort(bPort2.getId())
                .matchDlType(IPv4.ETHERTYPE)
                .setSimpleType(DtoRule.Drop).build();
        sleepBecause("we need the network to process the rule changes", 2);

        // ip4 can again send packets to ip1
        // NOTE: we change the IP addresses to avoid matching the previously
        // installed DROP flows - flow invalidation isn't implemented yet.
        icmpFromTapArrivesAtTap(tap4, tap3, mac4, mac3, ip4, ip1);
        // mac5 can again send packets to mac2
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip3, ip2);
        // Anyone (except mac1) can again send LLDP packets to anyone else.
        lldpFromTapArrivesAtTap(tap3, tap4, mac3, mac4);
        lldpFromTapArrivesAtTap(tap4, tap1, mac4, mac1);
        lldpFromTapArrivesAtTap(tap4, tap2, mac4, mac2);

        // ICMPs from mac1 should now be dropped.
        icmpFromTapDoesntArriveAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        // ARPs from mac1 will also be dropped.
        assertThat("The ARP request should have been sent.",
                tap1.send(PacketHelper.makeArpRequest(mac1, ip1, rtrIp)));
        assertThat("There should be no ARP reply.",
                tap1.recv(), nullValue());

        // ICMPs ingressing on tap2 should now be dropped.
        icmpFromTapDoesntArriveAtTap(tap2, tap3, mac2, mac3, ip2, ip3);
        // But ARPs ingressing on tap2 still work.
        assertThat("Tap2 should still be able to resolve the Router's IP/MAC.",
                rtrMac, equalTo(arpFromTapAndGetReply(tap2, mac2, ip2, rtrIp)));

        // Finally, remove bridge1's inboundFilter.
        bridge1.setInboundFilter(null);
        sleepBecause("we need the network to process the filter changes", 2);

        // ICMPs from mac1 are again delivered
        icmpFromTapArrivesAtTap(tap1, tap4, mac1, mac4, ip1, ip4);
        // ARPs ingressing on tap1 are again delivered
        byte[] pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);
        assertThat("The ARP should have been sent from tap1.", tap1.send(pkt));
        // The ARP should be flooded.
        assertThat("The packet should NOT have arrived at tap1.",
                tap1.recv(), nullValue());
        assertThat("The packet should have arrived at tap2.",
                tap2.recv(), allOf(notNullValue(), equalTo(pkt)));
        assertThat("The packet should have arrived at tap3.",
                tap3.recv(), allOf(notNullValue(), equalTo(pkt)));
        assertThat("The packet should have arrived at tap4.",
                tap4.recv(), allOf(notNullValue(), equalTo(pkt)));
        assertThat("The packet should have arrived at tap5.",
                tap5.recv(), allOf(notNullValue(), equalTo(pkt)));

        // ICMPs ingressing on tap2 are again delivered.
        icmpFromTapArrivesAtTap(tap2, tap4, mac2, mac4, ip2, ip4);

        // TODO(pino): Add a Rule that drops IPv6 packets (Ethertype 0x86DD)
        // TODO:       Show that IPv6 is forwarded before the rule is installed,
        // TODO:       and dropped afterwards. This will also check that using
        // TODO:       (signed) Short for dlType is correctly handled by Java.
    }
}
