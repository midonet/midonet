/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.Rule;
import com.midokura.midonet.functional_test.topology.RuleChain;
import com.midokura.midonet.functional_test.topology.TapWrapper;


import static com.midokura.midonet.functional_test.EndPoint.*;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class FlowInvalidationTest extends RouterBridgeBaseTest {

    @After
    public void tearDown() throws InterruptedException {
        router1.removeFilters();
        bridge1.removeFilters();
        for (BridgePort bport : bports)
            bport.removeFilters();
        for (EndPoint ep : vmEndpoints)
            ep.floatingIp = null;
        sleepBecause("We need the all the chains/filters to be unloaded", 2);
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

    @Ignore
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
