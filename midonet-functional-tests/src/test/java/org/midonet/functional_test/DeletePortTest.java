/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.Ignore;
import org.junit.Test;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.midolman.FlowController;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.midonet.util.Waiters.sleepBecause;

@Ignore
public class DeletePortTest extends TestBase {

    private final static Logger log = LoggerFactory.getLogger(
        DeletePortTest.class);

    IPv4Subnet ip1 = new IPv4Subnet("192.168.1.1", 24);
    IPv4Subnet ip2 = new IPv4Subnet("192.168.2.1", 24);
    IPv4Subnet ip3 = new IPv4Subnet("192.168.3.1", 24);

    Router rtr;
    RouterPort p1;
    RouterPort p2;
    RouterPort p3;

    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;

    IPv4Subnet tap1Ip = new IPv4Subnet("192.168.1.5", 24);
    IPv4Subnet tap2Ip = new IPv4Subnet("192.168.2.5", 24);
    IPv4Subnet tap3Ip = new IPv4Subnet("192.168.3.5", 24);

    MAC tap1Mac = MAC.fromString("02:00:00:aa:aa:01");
    MAC tap2Mac = MAC.fromString("02:00:00:aa:aa:02");
    MAC tap3Mac = MAC.fromString("02:00:00:aa:aa:03");

    @Override
    public void setup() {

        rtr = apiClient.addRouter().tenantId("tenant-id").name("rtr1").create();

        p1 = rtr.addExteriorRouterPort()
                .portAddress(ip1.toUnicastString())
                .networkAddress(ip1.toNetworkAddress().toString())
                .networkLength(ip1.getPrefixLen())
                .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr(p1.getNetworkAddress())
                .dstNetworkLength(p1.getNetworkLength())
                .nextHopPort(p1.getId())
                .type(DtoRoute.Normal).weight(10).create();

        tap1 = new TapWrapper("tap1");
        log.debug("Bind tap to router's exterior port.");
        thisHost.addHostInterfacePort()
                .interfaceName(tap1.getName())
                .portId(p1.getId()).create();

        log.info("Waiting for the port to start up.");
        LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
        log.info("Received one LocalPortActive message from stream.");
        assertTrue("The port should be active.", activeMsg.active());

        p2 = rtr.addExteriorRouterPort()
                .portAddress(ip2.toUnicastString())
                .networkAddress(ip2.toNetworkAddress().toString())
                .networkLength(ip2.getPrefixLen())
                .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr(p2.getNetworkAddress())
                .dstNetworkLength(p2.getNetworkLength())
                .nextHopPort(p2.getId())
                .type(DtoRoute.Normal).weight(10).create();

        tap2 = new TapWrapper("tap2");
        log.debug("Bind tap to router's exterior port.");
        thisHost.addHostInterfacePort()
                .interfaceName(tap2.getName())
                .portId(p2.getId()).create();

        log.info("Waiting for the port to start up.");
        activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
        log.info("Received one LocalPortActive message from stream.");
        assertTrue("The port should be active.", activeMsg.active());

        p3 = rtr.addExteriorRouterPort()
                .portAddress(ip3.toUnicastString())
                .networkAddress(ip3.toNetworkAddress().toString())
                .networkLength(ip3.getPrefixLen())
                .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr(p3.getNetworkAddress())
                .dstNetworkLength(p3.getNetworkLength())
                .nextHopPort(p3.getId())
                .type(DtoRoute.Normal).weight(10).create();
        tap3 = new TapWrapper("tap3");
        log.debug("Bind tap to router's exterior port.");
        thisHost.addHostInterfacePort()
                .interfaceName(tap3.getName())
                .portId(p3.getId()).create();

        log.info("Waiting for the port to start up.");
        activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
        log.info("Received one LocalPortActive message from stream.");
        assertTrue("The port should be active.", activeMsg.active());

        // simple check that everything is fine.
        try {
            FunctionalTestsHelper.arpAndCheckReply(tap1, tap1Mac,
                    tap1Ip.getAddress(), ip1.getAddress(),
                    MAC.fromString(p1.getPortMac()));
            FunctionalTestsHelper.arpAndCheckReply(tap2, tap2Mac,
                    tap2Ip.getAddress(), ip2.getAddress(),
                    MAC.fromString(p2.getPortMac()) );
            FunctionalTestsHelper.arpAndCheckReply(tap3, tap3Mac,
                    tap3Ip.getAddress(), ip3.getAddress(),
                    MAC.fromString(p3.getPortMac()));
        } catch (MalformedPacketException e) {
            fail(e.getMessage());
        }
    }

    @Override
    protected void teardown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
    }

    @Test
    public void testPortDelete()
        throws InterruptedException, MalformedPacketException {

        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), FlowController.WildcardFlowRemoved.class);


        // send two pings so two flows are installed:
        // tap1 --> tap3
        // tap2 --> tap3

        PacketHelper helper1 = new PacketHelper(
            tap1Mac, tap1Ip.getAddress(),
            MAC.fromString(p1.getPortMac()), ip1.getAddress());

        // Send ICMPs from p1 to internal port p3.
        byte[] ping1_3 = helper1.makeIcmpEchoRequest(ip3.getAddress());

        assertThat("The tap should have sent the packet",
                   tap1.send(ping1_3));

        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping1_3, tap1.recv());

        PacketHelper helper2 = new PacketHelper(
                tap2Mac, tap2Ip.getAddress(),
                MAC.fromString(p2.getPortMac()), ip2.getAddress());

        // Send ICMPs from p2 to internal port p3.
        byte[] ping2_3 = helper2.makeIcmpEchoRequest(tap3Ip.getAddress());
        assertThat("The tap should have sent the packet", tap2.send(ping2_3));
        log.info("Sending the second ping");

        sleepBecause("Wait for all the packets to be sent and the flows installed.",5);

        // Removing port3 in the router should invalidate two flows.
        log.info("Deleting the router port.");
        p3.delete();
        rtr.update();

        // port (tap3) going down
        probe.expectMsgClass(LocalPortActive.class);
        // flow invalidation 1
        probe.expectMsgClass(FlowController.WildcardFlowRemoved.class);
        // flow invalidation 2
        probe.expectMsgClass(FlowController.WildcardFlowRemoved.class);
        // no more messages.
        probe.expectNoMsg();
    }
}
