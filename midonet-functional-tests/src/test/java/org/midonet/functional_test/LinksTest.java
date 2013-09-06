/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.concurrent.TimeUnit;

import akka.testkit.TestProbe;
import akka.util.Duration;
import org.junit.Assert;
import org.junit.Test;
import org.midonet.packets.IPv4Subnet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.Matchers.equalTo;
import static org.midonet.functional_test.FunctionalTestsHelper.arpAndCheckReply;
import static org.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static org.midonet.functional_test.FunctionalTestsHelper.getEmbeddedMidolman;
import static org.midonet.functional_test.FunctionalTestsHelper.icmpFromTapDoesntArriveAtTap;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.client.dto.DtoExteriorRouterPort;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.Route;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.ICMP;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.util.lock.LockHelper;

public class LinksTest extends TestBase {

    private final static Logger log = LoggerFactory.getLogger(LinksTest.class);

    final IPv4Subnet rtrIp1 = new IPv4Subnet("192.168.111.1", 24);
    final IPv4Subnet vm1IP = new IPv4Subnet("192.168.111.2", 24);

    final IPv4Subnet rtrIp2 = new IPv4Subnet("192.168.222.1", 24);
    final IPv4Subnet vm2IP = new IPv4Subnet("192.168.222.2", 24);

    final MAC vm1Mac = MAC.fromString("02:aa:bb:cc:dd:d1");
    final MAC vm2Mac = MAC.fromString("02:aa:bb:cc:dd:d2");
    MAC rtrMac1;
    MAC rtrMac2;

    final String TENANT_NAME = "tenant-link";
    Router rtr;
    RouterPort<DtoExteriorRouterPort> rtrPort1;
    RouterPort<DtoExteriorRouterPort> rtrPort2;

    TapWrapper tap1;
    TapWrapper tap2;
    private final String TAP1NAME = "tapLink1";
    private final String TAP2NAME = "tapLink2";

    private PacketHelper helper1;
    private PacketHelper helper2;

    ApiServer apiStarter;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9" ;

    @Override
    public void setup() {

        EmbeddedMidolman mm = getEmbeddedMidolman();
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
            probe.ref(), LocalPortActive.class);

        rtr = apiClient.addRouter().tenantId(TENANT_NAME)
                       .name("rtr1").create();
        // Add a exterior port.
        rtrPort1 = rtr.addExteriorRouterPort()
                      .portAddress(rtrIp1.toUnicastString())
                      .networkAddress(rtrIp1.toNetworkAddress().toString())
                      .networkLength(rtrIp1.getPrefixLen())
                      .create();

        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
           .dstNetworkAddr(vm1IP.toUnicastString())
           .dstNetworkLength(vm1IP.getPrefixLen())
           .nextHopPort(rtrPort1.getId())
           .type(DtoRoute.Normal).weight(10).create();

        // Add a interior port to the router.
        rtrPort2 = rtr.addExteriorRouterPort()
                      .portAddress(rtrIp2.toUnicastString())
                      .networkAddress(rtrIp2.toNetworkAddress().toString())
                      .networkLength(rtrIp2.getPrefixLen())
                      .create();

        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
           .dstNetworkAddr(vm2IP.toUnicastString())
           .dstNetworkLength(vm2IP.getPrefixLen())
           .nextHopPort(rtrPort2.getId())
           .type(DtoRoute.Normal).weight(10)
           .create();

        rtrMac1 = MAC.fromString(rtrPort1.getPortMac());
        rtrMac2 = MAC.fromString(rtrPort2.getPortMac());

        ResourceCollection<Route> routes = rtr.getRoutes(null);
        Assert.assertThat("Router doesn't contain the expected routes",
                          routes.size(), equalTo(4));

        // Now bind the exterior ports to interfaces on the local host.

        log.debug("Creating taps");
        tap1 = new TapWrapper(TAP1NAME);
        tap2 = new TapWrapper(TAP2NAME);
        log.debug("Bind tap to router's exterior port.");
        thisHost.addHostInterfacePort()
                .interfaceName(tap1.getName())
                .portId(rtrPort1.getId()).create();
        thisHost.addHostInterfacePort()
                .interfaceName(tap2.getName())
                .portId(rtrPort2.getId()).create();

        log.info("Waiting for the port to start up.");
        for (int i = 0; i < 2; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
        }

        helper1 = new PacketHelper(
                vm1Mac, vm1IP.getAddress(), rtrIp1.getAddress());
        helper1.setGwMac(rtrMac1);
        helper2 = new PacketHelper(
                vm2Mac, vm2IP.getAddress(), rtrIp2.getAddress());
        helper2.setGwMac(rtrMac2);
    }

    @Override
    public void teardown() {
        log.info("Starting the custom teardown.");
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
    }

    private void doPingsToVms()
            throws MalformedPacketException, InterruptedException {
        byte[] req;
        log.info("Ping vm1 -> vm2");
        req = helper1.makeIcmpEchoRequest(vm2IP.getAddress());
        tap1.send(req);
        log.info("Expecting ICMP request on vm2");
        helper2.checkIcmpEchoRequest(req, tap2.recv());
        log.info("Ping vm2 -> vm1");
        req = helper2.makeIcmpEchoRequest(vm1IP.getAddress());
        tap2.send(req);
        log.info("Expecting ICMP request on vm2");
        helper1.checkIcmpEchoRequest(req, tap1.recv());
    }

    private void doPingsToRouter() throws MalformedPacketException {
        byte[] req;
        log.info("Ping vm1 -> rtrIp2");
        req = helper2.makeIcmpEchoRequest(rtrIp1.getAddress());
        tap2.send(req);
        PacketHelper.checkIcmpEchoReply(req, tap2.recv(), rtrIp1.getAddress());
        log.info("Ping vm2 -> rtrIp1");
        req = helper1.makeIcmpEchoRequest(rtrIp2.getAddress());
        tap1.send(req);
        PacketHelper.checkIcmpEchoReply(req, tap1.recv(), rtrIp2.getAddress());
    }

    @Test
    public void testMakePing()
        throws MalformedPacketException, InterruptedException {

        // First arp for router's macs
        arpAndCheckReply(tap1, vm1Mac, vm1IP.getAddress(),
                rtrIp1.getAddress(), rtrMac1);
        arpAndCheckReply(tap2, vm2Mac, vm2IP.getAddress(),
                rtrIp2.getAddress(), rtrMac2);

        assertNoMorePacketsOnTap(tap1);
        assertNoMorePacketsOnTap(tap2);

        // Send a few pings accross the router, no ARPs expected
        // because we have fed the ARP table already
        doPingsToVms();
        doPingsToRouter();

        assertNoMorePacketsOnTap(tap1);
        assertNoMorePacketsOnTap(tap2);

        log.info("Bringing tap2 down");
        EmbeddedMidolman mm = getEmbeddedMidolman();
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
            probe.ref(), LocalPortActive.class);

        tap2.down();
        log.info("Waiting for MM to realize that the port has gone down.");
        LocalPortActive lpa = probe.expectMsgClass(LocalPortActive.class);
        assertFalse(lpa.active());
        assertEquals(lpa.portID(), rtrPort2.getId());

        // Let router catch up with reality
        // TODO(galo) These sleeps are a very suboptimal solution.
        // Instead, catch when the route table has been updated - this can be
        // done by publishing an event in RouterManager, but is still vulnerable
        // to race conditions. A better option would be probably to listen to
        // changes on the router.
        Thread.sleep(250);

        byte[] req = null;
        log.info("Sending ping request to the tap that went down.");
        log.info("Ping vm1 -> rtrIp2");
        req = icmpFromTapDoesntArriveAtTap(tap1, tap2, vm1Mac, rtrMac1,
                vm1IP.getAddress(), rtrIp2.getAddress());
        PacketHelper.checkIcmpError(tap1.recv(), ICMP.UNREACH_CODE.UNREACH_NET,
                                    rtrMac1, rtrIp1.getAddress(),
                                    vm1Mac, vm1IP.getAddress(), req);
        log.info("Sending ping request to the network that went down.");
        log.info("Ping vm1 -> vm2");
        req = icmpFromTapDoesntArriveAtTap(tap1, tap2, vm1Mac, rtrMac1,
                vm1IP.getAddress(), vm2IP.getAddress());
        PacketHelper.checkIcmpError(tap1.recv(), ICMP.UNREACH_CODE.UNREACH_NET,
                                    rtrMac1, rtrIp1.getAddress(),
                                    vm1Mac, vm1IP.getAddress(), req);

        log.info("Bringing the tap up again");
        tap2.up();
        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertTrue(lpa.active());
        assertEquals(lpa.portID(), rtrPort2.getId());

        // Let router catch up with reality
        Thread.sleep(250);

        // Repeat pings to ensure that everything is back to normal
        doPingsToVms();
        doPingsToRouter();

        log.info("Deleting the tap.");
        removeTapWrapper(tap2);

        log.info("Waiting for MM to realize that the tap was deleted.");
        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertFalse(lpa.active());
        assertEquals(lpa.portID(), rtrPort2.getId());

        // Let router catch up with reality
        Thread.sleep(250);

        log.info("Sending ping request to the tap that went down.");
        log.info("Ping vm1 -> rtrIp2");
        req = helper1.makeIcmpEchoRequest(rtrIp2.getAddress());
        tap1.send(req);
        PacketHelper.checkIcmpError(tap1.recv(), ICMP.UNREACH_CODE.UNREACH_NET,
                                    rtrMac1, rtrIp1.getAddress(),
                                    vm1Mac, vm1IP.getAddress(), req);

        log.info("Resurrecting the tap.");
        tap2 = new TapWrapper(TAP2NAME);

        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertTrue(lpa.active());
        assertEquals(lpa.portID(), rtrPort2.getId());

        // Let router catch up with reality
        Thread.sleep(250);

        doPingsToVms();
        doPingsToRouter();

        log.info("Deleting the tap and don't wait for next interface scanner");
        removeTapWrapper(tap2);
        Thread.sleep(250);
        log.info("Resurrecting the tap.");
        tap2 = new TapWrapper(TAP2NAME);

        log.info("Waiting for MM to realize that the port has gone down.");
        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertTrue(lpa.active());
        assertEquals(lpa.portID(), rtrPort2.getId());

        // Let router catch up with reality
        Thread.sleep(500);

        doPingsToVms();
        doPingsToRouter();
    }
}
