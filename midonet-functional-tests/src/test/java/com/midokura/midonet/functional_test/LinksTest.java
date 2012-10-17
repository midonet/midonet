/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import akka.testkit.TestProbe;
import akka.util.Duration;
import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.DtoMaterializedRouterPort;
import com.midokura.midonet.client.dto.DtoRoute;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midonet.client.resource.RouterPort;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

/**
 *  So for example, you could set up a virtual topology with a few materialized ports, bind them to some taps, move
 *  packets between them, then put one of the taps in state down via an 'ip link set' command... and then make sure
 *  traffic flows correctly elsewhere... e.g. on a router the sender would get an ICMP net unreachable if the route was
 *  taken out of the forwarding table as a result of the link going down.
 */
public class LinksTest {

    private EmbeddedMidolman mm;
    private final static Logger log = LoggerFactory.getLogger(LinksTest.class);

    IntIPv4 rtrIp1 = IntIPv4.fromString("192.168.111.1", 24);
    IntIPv4 rtrIp2 = IntIPv4.fromString("192.168.222.1", 24);

    IntIPv4 vm1IP = IntIPv4.fromString("192.168.111.2", 24);
    IntIPv4 vm2IP = IntIPv4.fromString("192.168.222.2", 24);

    MAC vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03");
    final String TENANT_NAME = "tenant-link";

    RouterPort<DtoMaterializedRouterPort> rtrPort1;
    RouterPort<DtoMaterializedRouterPort> rtrPort2;

    TapWrapper tap1;
    TapWrapper tap2;

    MockMgmtStarter apiStarter;

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
        apiStarter = new MockMgmtStarter(zookeeperPort);
        MidonetMgmt apiClient = new MidonetMgmt(apiStarter.getURI());

        log.info("Starting midolman");
        mm = startEmbeddedMidolman(testConfigurationPath);
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);

        // Build a router
        //////////////////////////////////////////////////////////////////
        Router rtr1 = apiClient.addRouter().tenantId(TENANT_NAME)
                .name("rtr1").create();
        // Add a materialized port.
        rtrPort1 = rtr1.addMaterializedRouterPort()
                .portAddress(rtrIp1.toUnicastString())
                .networkAddress(rtrIp1.toNetworkAddress().toUnicastString())
                .networkLength(rtrIp1.getMaskLength())
                .create();
        rtr1.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr(rtrPort1.getNetworkAddress())
                .dstNetworkLength(rtrPort1.getNetworkLength())
                .nextHopPort(rtrPort1.getId())
                .type(DtoRoute.Normal).weight(10)
                .create();

        // Add a logical port to the router.
        rtrPort2 = rtr1.addMaterializedRouterPort()
                .portAddress(rtrIp2.toUnicastString())
                .networkAddress(rtrIp2.toNetworkAddress().toUnicastString())
                .networkLength(rtrIp2.getMaskLength())
                .create();
        rtr1.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
                .dstNetworkAddr(rtrPort2.getNetworkAddress())
                .dstNetworkLength(rtrPort2.getNetworkLength())
                .nextHopPort(rtrPort2.getId())
                .type(DtoRoute.Normal).weight(10)
                .create();

        // Now bind the materialized ports to interfaces on the local host.
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
        tap1 = new TapWrapper("tapLink1");
        tap2 = new TapWrapper("tapLink2");


        log.debug("Bind tap to router's materialized port.");
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
        log.info("STARTING THE TEST TEARDOWN");
        removeTapWrapper(tap1);
        //removeTapWrapper(tap2);
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void testMakePing()
            throws MalformedPacketException, InterruptedException {

        PacketHelper helper1 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:01"), vm1IP, rtrIp1);
        PacketHelper helper2 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:03"), vm2IP, rtrIp2);
        byte[] request;

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                tap2.send(helper2.makeArpRequest()));

        MAC rtrMac = helper2.checkArpReply(tap2.recv());
        helper2.setGwMac(rtrMac);

        // Ping near router port.
        log.info("Send the first PING");
        request = helper2.makeIcmpEchoRequest(rtrIp2);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));

        // The router does not ARP before delivering the echo reply because
        // our ARP request seeded the ARP table.
        log.info("Checking the first PING");
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        // Ping far router port.
        log.info("Send the second PING");
        request = helper2.makeIcmpEchoRequest(rtrIp1);
        assertThat(String.format("The tap %s should have sent the packet",
                tap2.getName()), tap2.send(request));

        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        log.info("Checking the second PING");
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        assertNoMorePacketsOnTap(tap2);

        // now let's test what happens if we bring the tap down.
        //removeTapWrapper(tap2);
        tap2.down();
        // wait for MM to realize that the port has gone down.
        log.info("Waiting for MM to realize that the port has gone down.");
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);
        LocalPortActive lpa = probe.expectMsgClass(LocalPortActive.class);
        assertFalse(lpa.active());
        log.info("Intercepted LOCALPORTACTIVE message: " + lpa.portID() + " --> " + lpa.active());

        // Ping far router port.
        log.info("Send the third PING");
        request = helper2.makeIcmpEchoRequest(rtrIp1);

        try {
            tap2.send(request);
            fail("Tap should have been deleted.");
        } catch (RuntimeException e) { }

        // bring the tap up again.


    }
}