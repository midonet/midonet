/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.concurrent.TimeUnit;

import akka.testkit.TestProbe;
import akka.util.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.FlowController;
import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.MidonetApi;
import com.midokura.midonet.client.dto.DtoExteriorRouterPort;
import com.midokura.midonet.client.dto.DtoRoute;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Route;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midonet.client.resource.RouterPort;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.ICMP;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.testng.Assert.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

@Ignore
public class LinksTest {

    private EmbeddedMidolman mm;
    private final static Logger log = LoggerFactory.getLogger(LinksTest.class);

    IntIPv4 rtrIp1 = IntIPv4.fromString("192.168.111.1", 24);
    IntIPv4 vm1IP = IntIPv4.fromString("192.168.111.2", 24);

    IntIPv4 rtrIp2 = IntIPv4.fromString("192.168.222.1", 24);
    IntIPv4 vm2IP = IntIPv4.fromString("192.168.222.2", 24);

    final String TENANT_NAME = "tenant-link";

    RouterPort<DtoExteriorRouterPort> rtrPort1;
    RouterPort<DtoExteriorRouterPort> rtrPort2;

    TapWrapper tap1;
    TapWrapper tap2;
    private final String TAP1NAME = "tapLink1";
    private final String TAP2NAME = "tapLink2";

    ApiServer apiStarter;
    Router rtr;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9" ;

    @Before
    public void setUp() throws Exception {

        String testConfigurationPath =
                "midolmanj_runtime_configurations/midolman-default.conf";

        // start zookeeper with the designated port.
        log.info("Starting embedded zookeeper.");
        int zookeeperPort = startEmbeddedZookeeper(testConfigurationPath);
        Assert.assertThat(zookeeperPort, greaterThan(0));

        log.info("Starting cassandra");
        startCassandra();

        log.info("Starting REST API");
        apiStarter = new ApiServer(zookeeperPort);
        MidonetApi apiClient = new MidonetApi(apiStarter.getURI());

        log.info("Starting midolman");
        mm = startEmbeddedMidolman(testConfigurationPath);
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);

        // Build a router
        //////////////////////////////////////////////////////////////////
        rtr = apiClient.addRouter().tenantId(TENANT_NAME)
                .name("rtr1").create();
        // Add a exterior port.
        rtrPort1 = rtr.addExteriorRouterPort()
                .portAddress(rtrIp1.toUnicastString())
                .networkAddress(rtrIp1.toNetworkAddress().toUnicastString())
                .networkLength(rtrIp1.getMaskLength())
                .create();

        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr("192.168.111.2")
                .dstNetworkLength(vm1IP.getMaskLength())
                .nextHopPort(rtrPort1.getId())
                .type(DtoRoute.Normal).weight(10).create();

        // Add a interior port to the router.
        rtrPort2 = rtr.addExteriorRouterPort()
                .portAddress(rtrIp2.toUnicastString())
                .networkAddress(rtrIp2.toNetworkAddress().toUnicastString())
                .networkLength(rtrIp2.getMaskLength())
                .create();

        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr("192.168.222.2")
                .dstNetworkLength(vm2IP.getMaskLength())
                .nextHopPort(rtrPort2.getId())
                .type(DtoRoute.Normal).weight(10)
                .create();

        ResourceCollection<Route> routes = rtr.getRoutes(null);
        Assert.assertThat("Router doesn't contain the expected routes", routes.size(), equalTo(4));

        // Now bind the exterior ports to interfaces on the local host.
        log.debug("Getting host from REST API");
        ResourceCollection<Host> hosts = apiClient.getHosts();

        Host host = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                host = h;
            }
        }
        // check that we've actually found the test host.
        assertNotNull("Host is null", host);

        log.debug("Creating TAP");
        tap1 = new TapWrapper(TAP1NAME);
        tap2 = new TapWrapper(TAP2NAME);

        log.debug("Bind tap to router's exterior port.");
        host.addHostInterfacePort()
                .interfaceName(tap1.getName())
                .portId(rtrPort1.getId()).create();

        host.addHostInterfacePort()
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
    }

    @After
    public void tearDown() throws Exception {
        log.info("Starting the teardown.");
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        stopEmbeddedMidolman();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void testMakePing()
            throws MalformedPacketException, InterruptedException {


        PacketHelper helper2 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:02"), vm2IP, rtrIp2);
        byte[] request;

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                tap2.send(helper2.makeArpRequest()));

        MAC rtrMac = helper2.checkArpReply(tap2.recv());
        helper2.setGwMac(rtrMac);

        // ping from tap2 to rtrIp2
        //////////////////////////////////////////////////
        request = helper2.makeIcmpEchoRequest(rtrIp2);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));

        // The router does not ARP before delivering the echo reply because
        // our ARP request seeded the ARP table.
        log.info("Checking the first PING");
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        // Ping far router port.
        //////////////////////////////////////////////
        log.info("Send the second PING");
        request = helper2.makeIcmpEchoRequest(rtrIp1);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));

        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        log.info("Checking the second PING reply");
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        assertNoMorePacketsOnTap(tap2);

        // Ping router ip
        PacketHelper helper1 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:01"), vm1IP, rtrIp1);
        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                tap1.send(helper1.makeArpRequest()));

        rtrMac = helper1.checkArpReply(tap1.recv());
        helper1.setGwMac(rtrMac);

        // Ping vm2 ip.
        // make sure that the flow is installed by doing this twice.
        request = helper1.makeIcmpEchoRequest(vm2IP);
        tap1.send(request);
        helper2.checkIcmpEchoRequest(request, tap2.recv());
        tap1.send(request);
        helper2.checkIcmpEchoRequest(request, tap2.recv());

        // now let's test what happens if we bring the tap down.
        ///////////////////////////////////////////////////////////////
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);
        mm.getActorSystem().eventStream().subscribe(
                probe.ref(), FlowController.WildcardFlowRemoved.class);

        // this should bring the router's route down as it cannot get to the 222 network.
        tap2.down();
        log.info("Waiting for MM to realize that the port has gone down.");
        LocalPortActive lpa = probe.expectMsgClass(LocalPortActive.class);
        assertFalse(lpa.active());

        probe.expectMsgClass(FlowController.WildcardFlowRemoved.class);

        // send a request to a disconnected port.
        log.info("Sending the ping request to the tap that went down.");
        tap1.send(request);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        PacketHelper.checkIcmpError(tap1.recv(), ICMP.UNREACH_CODE.UNREACH_NET, rtrMac, rtrIp1, MAC.fromString("02:00:00:aa:aa:01"), vm1IP, request);
        //helper1.checkIcmpError(tap1.recv(), ICMP.UNREACH_CODE.UNREACH_NET, vm1IP, request);

        // ping the far router port.
        log.info("Sending the ping request to the far router port");
        request = helper1.makeIcmpEchoRequest(rtrIp2);
        tap1.send(request);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        log.info("Bring the tap up again");
        // bring the tap up again.
        tap2.up();
        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertTrue(lpa.active());

        probe.expectMsgClass(FlowController.WildcardFlowRemoved.class);


        // use tap2 to ping the router.
        //////////////////////////////////////////////////
        request = helper2.makeIcmpEchoRequest(rtrIp2);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        request = helper2.makeIcmpEchoRequest(rtrIp1);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        // use tap2 to ping other vm
        //////////////////////////////////////////////////
        log.info("Checking that tap2 can send pings to tap1");
        request = helper2.makeIcmpEchoRequest(vm1IP);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));
        helper1.checkIcmpEchoRequest(request, tap1.recv());

        // and now the other way round.
        log.info("Checking that tap1 can send pings to tap2");
        request = helper1.makeIcmpEchoRequest(rtrIp1);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());
        request = helper1.makeIcmpEchoRequest(rtrIp2);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        request = helper1.makeIcmpEchoRequest(vm2IP);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        helper2.checkIcmpEchoRequest(request, tap2.recv());

        // delete the tap
        log.info("Deleting the tap.");
        removeTapWrapper(tap2);

        log.info("Waiting for MM to realize that the port has gone down.");
        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertFalse(lpa.active());

        probe.expectMsgClass(FlowController.WildcardFlowRemoved.class);

        log.info("Sending the ping request to the tap that went down.");
        tap1.send(request);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        PacketHelper.checkIcmpError(tap1.recv(), ICMP.UNREACH_CODE.UNREACH_NET, rtrMac, rtrIp1, MAC.fromString("02:00:00:aa:aa:01"), vm1IP, request);

        log.info("Resurrecting the tap.");
        tap2 = new TapWrapper(TAP2NAME);

        lpa = probe.expectMsgClass(LocalPortActive.class);
        assertTrue(lpa.active());

        for (int i = 0; i < 4; i++) {
            // waiting for the flows to get created...
            probe.receiveOne(Duration.create(5, "seconds"));
        }

        log.info("Sending the ping request to the restarted tap.");
        request = helper1.makeIcmpEchoRequest(vm2IP);
        assertThat(String.format("The tap %s should have sent the packet",
                tap1.getName()), tap1.send(request));
        helper2.checkIcmpEchoRequest(request, tap2.recv());
    }
}
