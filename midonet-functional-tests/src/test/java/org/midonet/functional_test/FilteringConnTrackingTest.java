/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import org.junit.Test;

import org.midonet.client.dto.DtoRule;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.client.resource.Rule;
import org.midonet.client.resource.RuleChain;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;


import static org.midonet.functional_test.FunctionalTestsHelper.arpAndCheckReplyDrainBroadcasts;
import static org.midonet.functional_test.FunctionalTestsHelper.bindTapsToBridgePorts;
import static org.midonet.functional_test.FunctionalTestsHelper.buildBridgePorts;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.midonet.functional_test.FunctionalTestsHelper.udpFromTapArrivesAtTap;
import static org.midonet.functional_test.FunctionalTestsHelper.udpFromTapDoesntArriveAtTap;
import static org.midonet.util.Waiters.sleepBecause;

public class FilteringConnTrackingTest extends TestBase {
    Bridge bridge1;
    BridgePort interiorBrPort;
    RouterPort rPort1;
    TapWrapper[] taps;
    BridgePort[] ports;

    @Override
    public void setup() {
        bridge1 = apiClient.addBridge()
            .tenantId("tenant-1")
            .name("br1")
            .create();

        Router rtr = apiClient.addRouter()
            .tenantId("tenant-1")
            .name("rtr1")
            .create();
        // Link the Bridge and Router
        rPort1 = rtr.addPort()
                    .networkAddress("10.0.0.0")
                    .networkLength(24)
                    .portAddress("10.0.0.1").create();
        interiorBrPort = bridge1.addPort().create();
        rPort1.link(interiorBrPort.getId());

        ports = buildBridgePorts(bridge1, true, 5);
        taps = bindTapsToBridgePorts(thisHost, ports, "filterConnTap", probe);

    }

    @Override
    public void teardown() {
        if (taps != null) {
            for (TapWrapper tap : taps) {
                log.debug("Cleaning tap {}", tap);
                removeTapWrapper(tap);
            }
        }
        if (null != rPort1)
            rPort1.unlink();
    }

    @Test
    public void test() throws MalformedPacketException, InterruptedException {
        MAC mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        MAC mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        MAC mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        MAC mac4 = MAC.fromString("02:aa:bb:cc:dd:d4");
        MAC mac5 = MAC.fromString("02:aa:bb:cc:dd:d5");
        IPv4Addr rtrIp = IPv4Addr.fromString("10.0.0.1");
        IPv4Addr ip1 = IPv4Addr.fromString("10.0.0.2");
        IPv4Addr ip2 = IPv4Addr.fromString("10.0.0.3");
        IPv4Addr ip3 = IPv4Addr.fromString("10.0.0.4");
        IPv4Addr ip4 = IPv4Addr.fromString("10.0.0.5");
        IPv4Addr ip5 = IPv4Addr.fromString("10.0.0.6");
        final short port1 = 1;
        final short port2 = 2;
        final short port3 = 3;
        final short port4 = 4;
        final short port5 = 5;
        // Extract taps to variables for clarity (tap1 better than taps[0])
        final TapWrapper tap1 = taps[0];
        final TapWrapper tap2 = taps[1];
        final TapWrapper tap3 = taps[2];
        final TapWrapper tap4 = taps[3];
        final TapWrapper tap5 = taps[4];

        // Send ARPs from each edge port so that the bridge can learn MACs.
        MAC rtrMac = MAC.fromString(rPort1.getPortMac());
        arpAndCheckReplyDrainBroadcasts(tap1, mac1, ip1, rtrIp, rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(tap2, mac2, ip2, rtrIp, rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(tap3, mac3, ip3, rtrIp, rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(tap4, mac4, ip4, rtrIp, rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(tap5, mac5, ip5, rtrIp, rtrMac, taps);

        // tap3 (ip3, mac3) can send packets to (ip1, mac1) and (ip2, mac2).
        udpFromTapArrivesAtTap(tap3, tap1, mac3, mac1, ip3, ip1, port3, port1,
                               "three => one".getBytes());
        udpFromTapArrivesAtTap(tap3, tap2, mac3, mac2, ip3, ip2, port3, port2,
                               "three => two".getBytes());

        // Now create a chain for the L2 virtual bridge's inbound filter.
        RuleChain brInFilter = apiClient.addChain().tenantId("tenantId").name("brInFilter").create();
        // Add a rule that accepts all return packets.
        Rule rule0 = brInFilter.addRule().position(1).matchReturnFlow(true)
                               .type(DtoRule.Accept).create();
        // Add a rule that drops packets from ip4 to ip1. Because of the
        // previous rule, return packets from ip4 to ip1 will still pass.
        brInFilter.addRule().position(2)
            .nwSrcAddress(ip4.toString()).nwSrcLength(32)
            .nwDstAddress(ip1.toString()).nwDstLength(32)
            .type(DtoRule.Drop)
            .create();

        // Add a rule that drops packets from mac5 to mac2. Because of the
        // initial conn-tracking rule, return pkts from mac5 to mac2 still pass.
        brInFilter.addRule().position(3)
            .dlSrc(mac5.toString()).dlDst(mac2.toString())
            .type(DtoRule.Drop)
            .create();

        // Set this chain as the bridge's inbound filter.
        bridge1.inboundFilterId(brInFilter.getId()).update();

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
