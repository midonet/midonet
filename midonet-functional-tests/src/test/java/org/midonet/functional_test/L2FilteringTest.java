/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.UUID;

import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.client.resource.Rule;
import org.midonet.client.resource.RuleChain;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.LLDP;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.Unsigned;
import org.midonet.util.lock.LockHelper;

import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.midonet.util.Waiters.sleepBecause;

public class L2FilteringTest extends TestBase {

    IPv4Subnet rtrIp = new IPv4Subnet("10.0.0.254", 24);
    RouterPort rtrPort;
    Bridge bridge;
    BridgePort[] brPorts;
    TapWrapper[] taps;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID =
        "910de343-c39b-4933-86c7-540225fb02f9";

    @Override
    public void setup() {

        // Build a router
        Router rtr = apiClient.addRouter()
                              .tenantId("L2filter_tnt")
                              .name("rtr1")
                              .create();
        // Add a interior port to the router.
        rtrPort = rtr
            .addPort()
            .portAddress(rtrIp.toUnicastString())
            .networkAddress(rtrIp.toUnicastString())
            .networkLength(rtrIp.getPrefixLen())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort.getNetworkAddress())
            .dstNetworkLength(rtrPort.getNetworkLength())
            .nextHopPort(rtrPort.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        // Build a bridge
        bridge = apiClient.addBridge()
            .tenantId("L2filter_tnt")
            .name("br")
            .create();

        // Link the bridge to the router.
        BridgePort logBrPort =
            bridge.addPort().create();
        rtrPort.link(logBrPort.getId());


        // Add some exterior ports and bind to taps
        brPorts = buildBridgePorts(bridge, true, 5);
        taps = bindTapsToBridgePorts(
        thisHost, brPorts, "l2FilterTap", probe);

    }

    @Override
    public void teardown() {
        if (taps != null) {
            for (TapWrapper tap : taps) {
                removeTapWrapper(tap);
            }
        }
    }

    @Test
    public void test() throws MalformedPacketException, InterruptedException {
        MAC mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        MAC mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        MAC mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        MAC mac4 = MAC.fromString("02:aa:bb:cc:dd:d4");
        MAC mac5 = MAC.fromString("02:aa:bb:cc:dd:d5");
        IPv4Addr ip1 = IPv4Addr.fromString("10.0.0.1");
        IPv4Addr ip2 = IPv4Addr.fromString("10.0.0.2");
        IPv4Addr ip3 = IPv4Addr.fromString("10.0.0.3");
        IPv4Addr ip4 = IPv4Addr.fromString("10.0.0.4");
        IPv4Addr ip5 = IPv4Addr.fromString("10.0.0.5");

        // Extract taps to variables for clarity (tap1 better than taps[0])
        final TapWrapper tap1 = taps[0];
        final TapWrapper tap2 = taps[1];
        final TapWrapper tap3 = taps[2];
        final TapWrapper tap4 = taps[3];
        final TapWrapper tap5 = taps[4];

        // Send ARPs from each edge port so that the bridge can learn MACs.
        // If broadcasts happen, drain them
        MAC rtrMac = MAC.fromString(rtrPort.getPortMac());
        arpAndCheckReplyDrainBroadcasts(
                tap1, mac1, ip1, rtrIp.getAddress(), rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(
                tap2, mac2, ip2, rtrIp.getAddress(), rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(
                tap3, mac3, ip3, rtrIp.getAddress(), rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(
                tap4, mac4, ip4, rtrIp.getAddress(), rtrMac, taps);
        arpAndCheckReplyDrainBroadcasts(
                tap5, mac5, ip5, rtrIp.getAddress(), rtrMac, taps);

        // All traffic is allowed now.
        icmpFromTapArrivesAtTap(tap1, tap2, mac1, mac2, ip1, ip2);
        icmpFromTapArrivesAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        icmpFromTapArrivesAtTap(tap1, tap4, mac1, mac4, ip1, ip4);
        icmpFromTapArrivesAtTap(tap1, tap5, mac1, mac5, ip1, ip5);

        // And specifically this traffic - which we'll block very soon.
        // ip4 to ip1
        icmpFromTapArrivesAtTap(tap4, tap1, mac4, mac1, ip4, ip1);
        icmpFromTapArrivesAtTap(tap4, tap2, mac4, mac2, ip4, ip2);
        // mac5 to mac2
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip5, ip2);
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip4, ip2);

        // LLDP packets.
        lldpFromTapArrivesAtTap(tap3, tap2, mac3, mac2);
        lldpFromTapArrivesAtTap(tap2, tap1, mac2, mac1);
        lldpFromTapArrivesAtTap(tap5, tap4, mac5, mac4);

        // Now create a chain for the L2 virtual bridge's inbound filter.
        RuleChain brInFilter = apiClient.addChain()
            .name("brInFilter").tenantId("pgroup_tnt").create();
        // Add a rule that drops packets from ip4 to ip1.
        Rule rule1 = brInFilter.addRule().type(DtoRule.Drop)
            .nwSrcAddress(ip4.toString()).nwSrcLength(32)
            .nwDstAddress(ip1.toString()).nwDstLength(32)
            .create();
        // Add a rule that drops packets from mac5 to mac2.
        Rule rule2 = brInFilter.addRule().type(DtoRule.Drop)
            .dlSrc(mac5.toString()).dlDst(mac2.toString())
            .create();
        // Add a rule that drops LLDP packets.
        Rule rule3 = brInFilter.addRule().type(DtoRule.Drop)
            .dlType(Unsigned.unsign(LLDP.ETHERTYPE)).create();

        // Set this chain as the bridge's inbound filter.
        bridge.inboundFilterId(brInFilter.getId()).update();
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
        lldpFromTapDoesntArriveAtTap(tap5, tap4, mac5, mac4);

        // Now remove the previous rules.
        rule1.delete();
        rule2.delete();
        rule3.delete();
        // Add a rule the drops any packet from mac1.
        brInFilter.addRule().type(DtoRule.Drop)
            .dlSrc(mac1.toString()).create();
        // Add a rule that drops any IP packet that ingresses the third port.
        brInFilter.addRule().type(DtoRule.Drop)
            .inPorts(new UUID[]{brPorts[2].getId()})
            .dlType(Unsigned.unsign(IPv4.ETHERTYPE)).create();
        sleepBecause("we need the network to process the rule changes", 2);

        // The traffic that was blocked now passes:
        // ip4 to ip1
        icmpFromTapArrivesAtTap(tap4, tap1, mac4, mac1, ip4, ip1);
        icmpFromTapArrivesAtTap(tap4, tap2, mac4, mac2, ip4, ip2);
        // mac5 to mac2
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip5, ip2);
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip4, ip2);
        lldpFromTapArrivesAtTap(tap5, tap2, mac5, mac2);
        // LLDP packets
        lldpFromTapArrivesAtTap(tap3, tap2, mac3, mac2);
        lldpFromTapArrivesAtTap(tap2, tap1, mac2, mac1);
        lldpFromTapArrivesAtTap(tap5, tap4, mac5, mac4);

        // ICMPs from mac1 should now be dropped.
        icmpFromTapDoesntArriveAtTap(tap1, tap2, mac1, mac2, ip1, ip2);
        icmpFromTapDoesntArriveAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        // LLDP from mac1 should also be dropped.
        lldpFromTapDoesntArriveAtTap(tap1, tap4, mac1, mac4);
        lldpFromTapDoesntArriveAtTap(tap1, tap5, mac1, mac5);
        // ARPs from mac1 will also be dropped.
        assertThat("The ARP request should have been sent.",
            tap1.send(PacketHelper.makeArpRequest(
                    mac1, ip1, rtrIp.getAddress())));
        assertThat("No ARP reply since the request should not have arrived.",
            tap1.recv(), nullValue());
        // Other ARPs pass and are correctly resolved.
        arpAndCheckReply(tap2, mac2, ip2, rtrIp.getAddress(), rtrMac);
        arpAndCheckReply(tap4, mac4, ip4, rtrIp.getAddress(), rtrMac);

        // ICMPs ingressing on tap3 should now be dropped.
        icmpFromTapDoesntArriveAtTap(tap3, tap2, mac3, mac2, ip3, ip2);
        icmpFromTapDoesntArriveAtTap(tap3, tap4, mac3, mac4, ip3, ip4);
        icmpFromTapDoesntArriveAtTap(tap3, tap5, mac3, mac5, ip3, ip5);
        // But ARPs ingressing on tap3 may pass.
        arpAndCheckReply(tap3, mac3, ip3, rtrIp.getAddress(), rtrMac);
        // And LLDPs ingressing tap3 also pass.
        lldpFromTapArrivesAtTap(tap3, tap4, mac3, mac4);
        lldpFromTapArrivesAtTap(tap3, tap5, mac3, mac5);

        // Finally, remove bridge1's inboundFilter.
        bridge.inboundFilterId(null).update();
        sleepBecause("we need the network to process the filter changes", 2);

        // ICMPs from mac1 are again delivered
        icmpFromTapArrivesAtTap(tap1, tap2, mac1, mac2, ip1, ip2);
        icmpFromTapArrivesAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        // LLDP from mac1 are again passing.
        lldpFromTapArrivesAtTap(tap1, tap4, mac1, mac4);
        lldpFromTapArrivesAtTap(tap1, tap5, mac1, mac5);
        // ARPs ingressing on tap1 are again delivered
        arpAndCheckReply(tap1, mac1, ip1, rtrIp.getAddress(), rtrMac);

        // ICMPs ingressing on tap3 again pass.
        icmpFromTapArrivesAtTap(tap3, tap2, mac3, mac2, ip3, ip2);
        icmpFromTapArrivesAtTap(tap3, tap4, mac3, mac4, ip3, ip4);
        icmpFromTapArrivesAtTap(tap3, tap5, mac3, mac5, ip3, ip5);

        // TODO(pino): Add a Rule that drops IPv6 packets (Ethertype 0x86DD)
        // TODO:       Show that IPv6 is forwarded before the rule is installed,
        // TODO:       and dropped afterwards. This will also check that using
        // TODO:       (signed) Short for dlType is correctly handled by Java.
    }
}
