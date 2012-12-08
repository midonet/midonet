/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorRef;
import akka.testkit.TestProbe;
import akka.util.Duration;
import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midolman.topology.VirtualTopologyActor;
import com.midokura.midolman.topology.VirtualTopologyActor.RouterRequest;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.resource.Bgp;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midonet.client.resource.RouterPort;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.process.ProcessHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.util.Waiters.sleepBecause;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;

import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;

public class BgpTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    final String tenantName = "tenant";

    static final String networkNamespace = "bgptest_ns";
    static final String pairedInterfaceLocal = "bgptest0";
    static final String pairedInterfacePeer  = "bgptest1";
    static final String peerVm = "peerVmPort";

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    MidolmanLauncher midolman;

    Router router1;
    TapWrapper tap1_vm;

    PacketHelper packetHelper1;

    Bgp bgp1;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";


    @BeforeClass
    public static void setUpClass() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);

        String bgpPeerConfig = "midolmanj_runtime_configurations/peer.bgpd.conf";

        ProcessHelper.ProcessResult result;
        String cmdLine;

        // Create network namespace
        cmdLine = "/bin/sh -c \"sudo ip netns list | grep " + networkNamespace + "\"";
        result = ProcessHelper.executeCommandLine(cmdLine);
        log.debug("result.consoleOutput: {}", result.consoleOutput);
        log.debug("result.consoleOutput.size: {}", result.consoleOutput.size());
        if (result.consoleOutput.size() == 0) {
            cmdLine = "sudo ip netns add " + networkNamespace;
            ProcessHelper.executeCommandLine(cmdLine);
        } else {
            cmdLine = "sudo ip link delete " + pairedInterfaceLocal;
            ProcessHelper.executeCommandLine(cmdLine);

            cmdLine = "sudo killall bgpd";
            ProcessHelper.executeCommandLine(cmdLine);
        }

        // Create paired interfaces
        cmdLine = "sudo ip link add name " + pairedInterfaceLocal +
                " type veth peer name " + pairedInterfacePeer;
        ProcessHelper.executeCommandLine(cmdLine);

        cmdLine = "sudo ifconfig " + pairedInterfaceLocal + " up";
        ProcessHelper.executeCommandLine(cmdLine);

        // Send peer paired interface to network namespace
        cmdLine = "sudo ip link set " + pairedInterfacePeer + " netns " +
                networkNamespace;
        ProcessHelper.executeCommandLine(cmdLine);

        cmdLine = "sudo ip netns exec " + networkNamespace + " ifconfig " +
                pairedInterfacePeer + " 100.0.0.2 up";
        ProcessHelper.executeCommandLine(cmdLine);

        // bring up loopback in network namespace
        cmdLine = "sudo ip netns exec " + networkNamespace + " ifconfig lo up";
        ProcessHelper.executeCommandLine(cmdLine);

        // Create fake VM port in network namespace
        ProcessHelper.executeCommandLine("sudo ip link add name " + peerVm + " type dummy");
        ProcessHelper.executeCommandLine("sudo ip link set " + peerVm +
                " netns " + networkNamespace);
        ProcessHelper.executeCommandLine("sudo ip netns exec " + networkNamespace +
                " ifconfig " + peerVm + " 2.0.0.2 up");

        // run zebra
        cmdLine = "sudo ip netns exec " + networkNamespace + " zebra";
        ProcessHelper.RunnerConfiguration zebra_runner =
                ProcessHelper.newDemonProcess(cmdLine);
        zebra_runner.run();

        // run bgpd peer
        cmdLine = "sudo ip netns exec " +
                networkNamespace + " /usr/local/sbin/bgpd --config_file " +
                bgpPeerConfig + " --pid_file " +
                "/var/run/quagga/peer.bgpd.pid";
        ProcessHelper.RunnerConfiguration bgpd_runner =
                ProcessHelper.newDemonProcess(cmdLine);
        bgpd_runner.run();
    }

    @AfterClass
    public static void tearDownClass() {
        lock.release();

        ProcessHelper.executeCommandLine("sudo killall bgpd");
        ProcessHelper.executeCommandLine("sudo killall zebra");

        ProcessHelper.executeCommandLine("sudo ip link delete " +
                pairedInterfaceLocal);
        ProcessHelper.executeCommandLine("sudo ip netns exec " +
                networkNamespace + " ip link delete " + peerVm);
        ProcessHelper.executeCommandLine("sudo ip netns delete " +
                networkNamespace);
    }

    @Before
    public void setUp() throws InterruptedException, IOException {

        String testConfigurationPath = "midolmanj_runtime_configurations/midolman-with_bgp.conf";
        File testConfigurationFile = new File(testConfigurationPath);

        // start zookeeper with the designated port.
        log.info("Starting embedded zookeeper.");
        int zookeeperPort = startEmbeddedZookeeper(testConfigurationPath);
        assertThat(zookeeperPort, greaterThan(0));

        log.info("Starting cassandra");
        startCassandra();

        log.info("Starting REST API");
        apiStarter = new MockMgmtStarter(zookeeperPort);
        apiClient = new MidonetMgmt(apiStarter.getURI());

        log.info("Starting midolman");
        EmbeddedMidolman mm = startEmbeddedMidolman(
                testConfigurationFile.getAbsolutePath());
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);

        router1 = apiClient.addRouter().tenantId(tenantName).name("router1").create();
        log.debug("Created router " + router1.getName());

        RouterPort exteriorRouterPort1_vm = (RouterPort) router1.addExteriorRouterPort()
                .portAddress("1.0.0.1")
                .networkAddress("1.0.0.0")
                .networkLength(24)
                .portMac("02:00:00:00:01:01")
                .create();
        log.debug("Created exterior router port - VM: " + exteriorRouterPort1_vm.toString());

        router1.addRoute()
                .dstNetworkAddr("1.0.0.1")
                .dstNetworkLength(24)
                .srcNetworkAddr("0.0.0.0")
                .srcNetworkLength(0)
                .nextHopPort(exteriorRouterPort1_vm.getId())
                .type("Normal")
                .create();

        RouterPort exteriorRouterPort1_bgp = (RouterPort) router1.addExteriorRouterPort()
                .portAddress("100.0.0.1")
                .networkAddress("100.0.0.0")
                .networkLength(30)
                .portMac("02:00:00:00:aa:01")
                .create();
        log.debug("Created exterior router port - BGP: " + exteriorRouterPort1_bgp.toString());

        bgp1 = exteriorRouterPort1_bgp.addBgp()
                .localAS(1)
                .peerAddr("100.0.0.2")
                .peerAS(2)
                .create();
        log.debug("Created BGP {} in exterior router port {} ",
                bgp1.toString(), exteriorRouterPort1_bgp.toString());

        bgp1.addAdRoute()
                .nwPrefix("1.0.0.0")
                .prefixLength(24)
                .create();

        log.debug("Getting host from REST API");
        ResourceCollection<Host> hosts = apiClient.getHosts();

        Host host = null;
        for (Host h : hosts) {
            log.debug("Host: " + h.getId());
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                host = h;
                log.debug("Host match.");
            }
        }
        assertThat(host, notNullValue());

        log.debug("Creating TAP1 vm");
        tap1_vm = new TapWrapper("tap1_vm");

        log.debug("Adding interface to host.");
        host.addHostInterfacePort()
                .interfaceName(tap1_vm.getName())
                .portId(exteriorRouterPort1_vm.getId())
                .create();

        log.debug("Adding interface to host.");
        host.addHostInterfacePort()
                .interfaceName(pairedInterfaceLocal)
                .portId(exteriorRouterPort1_bgp.getId())
                .create();

        // Wait for the ports to become active
        for (int i = 0; i < 2; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                    Duration.create(10, TimeUnit.SECONDS),
                    LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
        }

        packetHelper1 = new PacketHelper(
                MAC.fromString("02:00:00:00:01:02"),
                IntIPv4.fromString("1.0.0.2"),
                MAC.fromString("02:00:00:00:01:01"),
                IntIPv4.fromString("1.0.0.1"));

        // This is just for ARP between fake VM and router, so the
        // router has the fake VM MAC
        PacketHelper helper1 = new PacketHelper(
                MAC.fromString("02:00:00:00:01:02"),
                IntIPv4.fromString("1.0.0.2"),
                IntIPv4.fromString("1.0.0.1"));

        // arp for router's mac
        assertThat("The ARP request was sent properly",
                tap1_vm.send(helper1.makeArpRequest()));

        try {
            MAC rtrMac = helper1.checkArpReply(tap1_vm.recv());
            helper1.setGwMac(rtrMac);
        } catch (MalformedPacketException e) {
            log.debug("bad packet: {}", e);
        }

    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1_vm);
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Ignore
    @Test
    public void testNoRouteConnectivity() throws Exception {
        log.debug("testNoRouteConnectivity - start");

        tap1_vm.send(packetHelper1.makeIcmpEchoRequest(IntIPv4.fromString("2.0.0.2")));

        log.debug("testNoRouteConnectivity - stop");
    }

    @Test
    public void testRouteConnectivity() throws Exception {
        log.debug("testRouteConnectivity - start");

        waitForBgp();

        byte [] request;
        request = packetHelper1.makeIcmpEchoRequest(IntIPv4.fromString("2.0.0.2"));
        assertThat(String.format("The tap %s should have sent the packet",
                tap1_vm.getName()), tap1_vm.send(request));

        sleepBecause("wait for ICMP to travel", 1);

        PacketHelper.checkIcmpEchoReply(request, tap1_vm.recv());

        log.debug("testRouteConnectivity - stop");
    }

    private void waitForBgp() {
        log.debug("begin");

        EmbeddedMidolman mm = getEmbeddedMidolman();
        assertThat(mm, notNullValue());

        TestProbe probe = new TestProbe(mm.getActorSystem());
        ActorRef vta = VirtualTopologyActor.getRef(mm.getActorSystem());

        RouterRequest routerRequest = new RouterRequest(router1.getId(), true);
        vta.tell(routerRequest, probe.ref());

        // Ask VTA to give us (TestProbe) the router, and keep us updated
        com.midokura.midolman.simulation.Router router =
                probe.expectMsgClass(Duration.create(10, TimeUnit.MILLISECONDS),
                  com.midokura.midolman.simulation.Router.class);

        log.debug("router id: {}", router.id());

        // We received the router, now lets wait for the router update.
        // The router is updated when it receives the routes from BGP.
        router = probe.expectMsgClass(Duration.create(30, TimeUnit.SECONDS),
                        com.midokura.midolman.simulation.Router.class);

        log.debug("end - router id: {}", router.id());
    }
}
