/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.*;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class FlowInvalidationTest {
    static MidolmanMgmt mgmt;
    static MidolmanLauncher midolman1;
    static OvsBridge ovsBridge1;
    static Tenant tenant1;
    static Router router1;
    static MaterializedRouterPort routerUplink;
    static EndPoint rtrUplinkEndpoint;
    static LogicalRouterPort routerDownlink;
    static Bridge bridge1;
    static final int numBridgePorts = 5;
    static LogicalBridgePort bridgeUplink;
    static List<BridgePort> bports = new ArrayList<BridgePort>();
    static List<EndPoint> vmEndpoints = new ArrayList<EndPoint>();
    static IntIPv4 floatingIP0 = IntIPv4.fromString("112.0.0.10");
    static IntIPv4 floatingIP1 = IntIPv4.fromString("112.0.0.20");

    private static class EndPoint {
        TapWrapper tap;
        MAC mac;
        MAC gwMac;
        IntIPv4 gwIp;
        IntIPv4 ip;
        IntIPv4 floatingIp;

        private EndPoint(IntIPv4 ip, MAC mac, IntIPv4 gwIp, MAC gwMac,
                         TapWrapper tap) {
            this.ip = ip;
            this.mac = mac;
            this.gwIp = gwIp;
            this.gwMac = gwMac;
            this.tap = tap;
            this.floatingIp = null;
        }
    }

    @BeforeClass
    public static void setUp() throws IOException, InterruptedException {
        OpenvSwitchDatabaseConnection ovsdb =
                new OpenvSwitchDatabaseConnectionImpl(
                        "Open_vSwitch", "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "FlowInvalidationTest");
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");

        // Create a tenant with a single router and bridge.
        tenant1 = new Tenant.Builder(mgmt).setName("tenant-l2filtering").build();

        // Create a router with an uplink.
        router1 = tenant1.addRouter().setName("rtr1").build();
        IntIPv4 gwIP = IntIPv4.fromString("172.16.0.2");
        TapWrapper rtrUplinkTap = new TapWrapper("routerUplink");
        routerUplink = router1.addGwPort()
                .setLocalMac(rtrUplinkTap.getHwAddr())
                .setLocalLink(IntIPv4.fromString("172.16.0.1"), gwIP)
                .addRoute(IntIPv4.fromString("0.0.0.0", 0)).build();
        rtrUplinkEndpoint = new EndPoint(gwIP, MAC.random(),
                routerUplink.getIpAddr(), routerUplink.getMacAddr(),
                rtrUplinkTap);
        ovsBridge1.addSystemPort(
                routerUplink.port.getId(),
                rtrUplinkTap.getName());

        // Create the bridge and link it to the router.
        bridge1 = tenant1.addBridge().setName("br1").build();
        routerDownlink = router1.addLinkPort()
                .setNetworkAddress("10.0.0.0")
                .setNetworkLength(24)
                .setPortAddress("10.0.0.1").build();
        bridgeUplink = bridge1.addLinkPort().build();
        routerDownlink.link(bridgeUplink);

        // Add ports to the bridge.
        for (int i = 0; i < numBridgePorts; i++) {
            bports.add(bridge1.addPort().build());
            vmEndpoints.add(new EndPoint(
                    IntIPv4.fromString("10.0.0.1" + i),
                    MAC.fromString("02:aa:bb:cc:dd:d" + i),
                    routerDownlink.getIpAddr(),
                    routerDownlink.getMacAddr(),
                    new TapWrapper("invalTap" + i)));
            ovsBridge1.addSystemPort(
                    bports.get(i).getId(),
                    vmEndpoints.get(i).tap.getName());
        }

        sleepBecause("we need the network to boot up", 10);
    }

    @AfterClass
    public static void tearDown() {
        removeTapWrapper(rtrUplinkEndpoint.tap);
        for (EndPoint ep : vmEndpoints)
            removeTapWrapper(ep.tap);

        removeBridge(ovsBridge1);
        stopMidolman(midolman1);

        if (null != routerDownlink)
            routerDownlink.unlink();
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    @After
    public void cleanVirtualDevices() throws InterruptedException {
        router1.removeFilters();
        bridge1.removeFilters();
        for (BridgePort bport : bports)
            bport.removeFilters();
        for (EndPoint ep : vmEndpoints)
            ep.floatingIp = null;
        sleepBecause("We need the all the chains/filters to be unloaded", 2);
    }

    private MAC exchangeArpWithGw(EndPoint ep)
            throws MalformedPacketException {
        assertThat("The ARP request was sent from the endpoint to the router.",
                ep.tap.send(PacketHelper.makeArpRequest(
                        ep.mac, ep.ip, ep.gwIp)));
        MAC dlDst = PacketHelper.checkArpReply(
                ep.tap.recv(), ep.gwIp, ep.mac, ep.ip);
        assertThat("The router's MAC is what we expected.",
                dlDst, equalTo(ep.gwMac));
        // Send unsolicited ARP replies so the router populates its ARP cache.
        assertThat("The unsolicited ARP reply was sent to the router.",
                ep.tap.send(PacketHelper.makeArpReply(
                        ep.mac, ep.ip, dlDst, ep.gwIp)));
        return dlDst;
    }

    private void icmpDoesntArrive(EndPoint sender, EndPoint receiver,
                                  PacketPair packets) {
        assertThat("The packet was sent from the sender's tap.",
                sender.tap.send(packets.sent));
        assertThat("No packet arrives at the intended receiver.",
                receiver.tap.recv(), nullValue());
    }

    private boolean sameSubnet(IntIPv4 ip1, IntIPv4 ip2) {
        // Assume all subnet masks are 24bits.
        int mask = 0xffffff00;
        return (ip1.getAddress() & mask) == (ip2.getAddress() & mask);
    }

    private void retrySentPacket(EndPoint sender, EndPoint receiver,
                                   PacketPair packets) {
        assertThat("The packet was sent.",
                sender.tap.send(packets.sent));
        assertThat("The packet arrived.",
                receiver.tap.recv(),
                allOf(notNullValue(), equalTo(packets.received)));
    }

    private PacketPair icmpTestOverBridge(EndPoint sender, EndPoint receiver)
            throws MalformedPacketException {
        return icmpTest(sender, receiver.ip, receiver, false);
    }

    private PacketPair icmpTest(EndPoint sender, IntIPv4 dstIp, EndPoint receiver,
                          boolean dstIpTranslated)
            throws MalformedPacketException {
        if (null == dstIp) {
            dstIp = receiver.ip;
            dstIpTranslated = false;
        }

        // Choose the dstMac based on whether dstIp is in the sender's subnet.
        boolean sameSubnet = sameSubnet(sender.ip, dstIp);
        byte[] sent = PacketHelper.makeIcmpEchoRequest(
                sender.mac, sender.ip,
                sameSubnet? receiver.mac : sender.gwMac, dstIp);
        assertThat("The packet should have been sent from the source tap.",
                sender.tap.send(sent));
        byte[] received = receiver.tap.recv();
        assertThat("The packet should have arrived at the destination tap.",
                received, notNullValue());
        Ethernet ethPkt = new Ethernet();
        ethPkt.deserialize(ByteBuffer.wrap(received));
        // The srcMac depends on whether dstIp is in the sender's subnet.
        assertThat("The received pkt's src Mac",
                ethPkt.getSourceMACAddress(),
                equalTo(sameSubnet? sender.mac : receiver.gwMac));
        assertThat("The received pkt's dst Mac should be the dst endpoint's",
                ethPkt.getDestinationMACAddress(), equalTo(receiver.mac));
        assertThat("It's an IP pkt.", ethPkt.getEtherType(),
                equalTo(IPv4.ETHERTYPE));
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        // The pkt's srcIp depends on whether the dstIp is in the sender's
        // subnet and whether the sender has a floatingIP.
        IntIPv4 srcIP = sameSubnet
                ? sender.ip
                : (sender.floatingIp != null)? sender.floatingIp : sender.ip;
        assertThat("The src IP", ipPkt.getSourceAddress(),
                equalTo(srcIP.getAddress()));
        // The pkt's nwDst depends on whether the dstIp is translated.
        assertThat("The dst IP is the dst endpoint's",
                ipPkt.getDestinationAddress(),
                equalTo(dstIpTranslated ?
                        receiver.ip.getAddress() : dstIp.getAddress()));

        // If we reset the fields that were translated, the packets should be
        // identical.
        ipPkt.setSourceAddress(sender.ip.getAddress());
        ipPkt.setDestinationAddress(dstIp.getAddress());
        // Reset the IPv4 pkt's checksum so that it's recomputed.
        ipPkt.setChecksum((short)0);
        ethPkt.setSourceMACAddress(sender.mac);
        ethPkt.setDestinationMACAddress(
                sameSubnet? receiver.mac : sender.gwMac);
        assertThat("The sent and received packet should now be identical.",
                ethPkt.serialize(), equalTo(sent));
        return new PacketPair(sent, received);
    }

    private static class PacketPair {
        byte[] sent;
        byte[] received;

        private PacketPair(byte[] sent, byte[] received) {
            this.sent = sent;
            this.received = received;
        }
    }

    @Test
    public void testRouterChanges()
            throws InterruptedException, MalformedPacketException {
        // Populate the bridge's MAC learning table and router's ARP cache.
        exchangeArpWithGw(vmEndpoints.get(0));
        exchangeArpWithGw(vmEndpoints.get(1));
        exchangeArpWithGw(rtrUplinkEndpoint);

        // Before any NAT is enabled, endpoint0 sends a packet to floatingIP1.
        // This goes to the router's uplink.
        PacketPair packets1 = icmpTest(
                vmEndpoints.get(0), floatingIP1, rtrUplinkEndpoint, false);
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets1);

        // Now assign a floatingIP to endpoint0.
        router1.addFilters();
        router1.addFloatingIp(vmEndpoints.get(0).ip, floatingIP0, null);
        vmEndpoints.get(0).floatingIp = floatingIP0;
        sleepBecause("The filter has to be loaded", 1);

        // Endpoint0 agains sends a packet to floatingIP1 (which is still
        // unassigned and therefore isn't NAT'ed. The packet still goes to the
        // uplink, but the source address at arrival is floatingIP0.
        PacketPair packets2 = icmpTest(
                vmEndpoints.get(0), floatingIP1, rtrUplinkEndpoint, false);
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets2);

        // Now assign floatingIP1 to endpoint1.
        router1.addFloatingIp(vmEndpoints.get(1).ip, floatingIP1, null);
        vmEndpoints.get(1).floatingIp = floatingIP1;
        sleepBecause("we need the new filters to be loaded", 2);

        // Now if endpoint0 sends the same ICMP to floatingIP1 it goes to
        // endpoint1. This shows that the previous flow match was deleted
        // when the new filters were added to the router.
        icmpTest(vmEndpoints.get(0), floatingIP1, vmEndpoints.get(1), true);
        assertThat("No packet arrived at the router uplink tap.",
                rtrUplinkEndpoint.tap.recv(), nullValue());

        // Now remove floatingIP1 from endpoint1.
        router1.removeFloatingIp(floatingIP1);
        vmEndpoints.get(1).floatingIp = null;
        sleepBecause("The network must process the rule deletion", 1);

        // Now endpoint0 sends the same ICMP to floatingIP1 and since the router
        // doesn't NAT floatingIP1, the packet again goes to the uplink.
        // This shows that the previous flow match was invalidated when
        // rules in the filter changed.
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets2);
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets2);

        // Stop NAT (including floatingIP0) by removing the router's filters.
        // This has the same effect as removing the rule, but shows that changes
        // to the router's configuration are detected and trigger invalidation.
        router1.removeFilters();
        sleepBecause("The network must process the filter removal.", 1);

        // Now endpoint0 sends the same ICMP to floatingIP1. The packet goes
        // to the uplink but the source address at arrival is endpoint0's.
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets1);
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets1);
    }

    @Test
    public void testBridgeChanges()
            throws InterruptedException, MalformedPacketException {
        // Populate the bridge's MAC learning table.
        exchangeArpWithGw(vmEndpoints.get(0));
        exchangeArpWithGw(vmEndpoints.get(1));

        // The bridge starts out without any filters, so anyone can talk to
        // anyone else. In particular endpoint0 can talk to endpoint1.
        PacketPair packets =
                icmpTestOverBridge(vmEndpoints.get(0), vmEndpoints.get(1));
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(1), packets);

        // Now add an inbound filter on the bridge and a rule that drops
        // traffic from endpoint0's ip to endpoint1's ip.
        RuleChain inFilter = bridge1.addInboundFilter();
        Rule rule1 = inFilter.addRule()
                .matchNwSrc(vmEndpoints.get(0).ip, 32)
                .matchNwDst(vmEndpoints.get(1).ip, 32)
                .setSimpleType(DtoRule.Drop).build();
        sleepBecause("we need the new filters to be loaded", 1);

        // Endpoint0 can no longer send that ICMP to endpoint1. This shows
        // that the previous flow match was deleted when the new filter was
        // added to the bridge.
        for (int i = 0; i < 3; i++)
            icmpDoesntArrive(vmEndpoints.get(0), vmEndpoints.get(1), packets);

        // Now leave the filters in place and remove the DROP rule.
        rule1.delete();
        sleepBecause("we need the filters to be reloaded", 1);

        // Now endpoint0 can again send that ICMP to endpoint1. This shows that
        // the previous flow match was deleted when the rule in the filter was
        // deleted.
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(1), packets);
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(1), packets);
    }

    @Test
    public void testPortChanges()
            throws InterruptedException, MalformedPacketException {
        // Populate the bridge's MAC learning table.
        exchangeArpWithGw(vmEndpoints.get(0));
        exchangeArpWithGw(vmEndpoints.get(1));
        exchangeArpWithGw(vmEndpoints.get(2));

        // Port1 starts out without any filters, so anyone can talk to it.
        PacketPair packets =
                icmpTestOverBridge(vmEndpoints.get(0), vmEndpoints.get(1));
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(1), packets);

        // Now add an outbound filter for endpoint1 and a rule that drops all
        // traffic from endpoint0's mac.
        RuleChain outFilter = bports.get(1)
                .addOutboundFilter("bport1_outfilter", tenant1.dto);
        Rule rule1 = outFilter.addRule()
                .matchNwSrc(vmEndpoints.get(0).ip, 32)
                .setSimpleType(DtoRule.Drop).build();
        sleepBecause("we need the new filters to be loaded", 1);

        // Endpoint0 can no longer send that ICMP to endpoint1. This shows
        // that the previous flow match was deleted when the new filter was
        // added to the port.
        for (int i = 0; i < 3; i++)
            icmpDoesntArrive(vmEndpoints.get(0), vmEndpoints.get(1), packets);

        // Check that the rule doesn't stop ICMPs from endpoint2 to endpoint1.
        icmpTestOverBridge(vmEndpoints.get(2), vmEndpoints.get(1));

        // Now leave the filter in place and remove the DROP rule.
        rule1.delete();
        sleepBecause("we need the filters to be reloaded", 1);

        // Now endpoint0 can again send the ICMP to endpoint1. This shows that
        // the previous flow match was deleted when the rule in the filter was
        // deleted.
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(1), packets);
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(1), packets);
    }

    @Test
    public void testMacLearning() throws MalformedPacketException {
        // Make sure that the other tests don't use endpoint4
        // so that its MAC can be learned only in this test.

        // Make sure that the bridge already learned macs for port0.
        exchangeArpWithGw(vmEndpoints.get(0));

        // If endpoint0 sends endpoint4 a packet, the bridge will flood it
        // because it has not yet learned the endpoint's MAC.
        PacketPair packets0to4 =
                icmpTestOverBridge(vmEndpoints.get(0), vmEndpoints.get(4));
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(4), packets0to4);
        // Check that endpoints 1, 2 and 3 also received the packet (twice).
        for (int i = 1; i < 4; i++) {
            assertThat("All the non-ingressPorts received the first packet",
                    vmEndpoints.get(i).tap.recv(),
                    allOf(notNullValue(), equalTo(packets0to4.received)));
            assertThat("All the non-ingressPorts received the second packet",
                    vmEndpoints.get(i).tap.recv(),
                    allOf(notNullValue(), equalTo(packets0to4.received)));
        }

        // Now send a packet from endpoint4 so the bridge can learn the mac.
        PacketPair packets4to0 =
            icmpTestOverBridge(vmEndpoints.get(4), vmEndpoints.get(0));

        // Now resend packet0to4. Only endpoint4 should receive it.
        // This shows that the FLOOD flow match was invalidated.
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(4), packets0to4);
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(4), packets0to4);
        for (int i = 0; i < 4; i++)
            assertThat("No packet arrives at endpoint " + i,
                    vmEndpoints.get(i).tap.recv(), nullValue());

        // Now resend the packet4to0 (with 4's Mac) from endpoint3. The bridge
        // learns that 4's Mac has moved to port3.
        retrySentPacket(vmEndpoints.get(3), vmEndpoints.get(0), packets4to0);

        // Now resend packet0to4. Only endpoint3 should receive it. This shows
        // that the unicast forward flow match (0 to 4) was invalidated.
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(3), packets0to4);
        retrySentPacket(vmEndpoints.get(0), vmEndpoints.get(3), packets0to4);
    }

    @Test
    public void testRoutingTableUpdate()
            throws MalformedPacketException, InterruptedException {
        // Populate the bridge's MAC learning table and router's ARP cache.
        exchangeArpWithGw(vmEndpoints.get(0));
        exchangeArpWithGw(rtrUplinkEndpoint);

        IntIPv4 pubNewIp = IntIPv4.fromString("112.0.1.40");
        // The router has no filters or NAT. So if endpoint0
        // tries to send an ICMP to pubNewIp, it will go to the uplink.
        PacketPair packets = icmpTest(
                vmEndpoints.get(0), pubNewIp, rtrUplinkEndpoint, false);
        retrySentPacket(vmEndpoints.get(0), rtrUplinkEndpoint, packets);

        // Now we add a router port with a route to floatingIP1
        TapWrapper tapNew = new TapWrapper("newRouterPort");
        IntIPv4 gwIp = IntIPv4.fromString("172.16.1.2");
        MaterializedRouterPort rtrNewPort = router1.addGwPort()
                .setLocalMac(tapNew.getHwAddr())
                .setLocalLink(IntIPv4.fromString("172.16.1.1"), gwIp)
                .addRoute(pubNewIp).build();
        EndPoint epNew = new EndPoint(gwIp, MAC.random(),
                rtrNewPort.getIpAddr(), rtrNewPort.getMacAddr(), tapNew);
        ovsBridge1.addSystemPort(
                rtrNewPort.port.getId(),
                tapNew.getName());
        sleepBecause("we need the new port to come up", 2);
        exchangeArpWithGw(epNew);

        // Now we resend the packet from endpoint0 to pubNewIp, it will go
        // to the new materialized router port. This shows that the previous
        // flow was invalidated.
        packets = icmpTest(
                vmEndpoints.get(0), pubNewIp, epNew, false);
        retrySentPacket(vmEndpoints.get(0), epNew, packets);
    }
}
