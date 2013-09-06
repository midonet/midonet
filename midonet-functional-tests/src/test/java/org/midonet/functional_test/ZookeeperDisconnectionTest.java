package org.midonet.functional_test;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorRef;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.pattern.Patterns;
import akka.testkit.TestProbe;
import akka.util.Duration;
import org.junit.Ignore;
import org.midonet.functional_test.utils.EmbeddedZKLauncher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.packets.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.Waiters;
import org.midonet.midolman.topology.FlowTagger;
import org.midonet.midolman.FlowController.WildcardFlowRemoved;
import org.midonet.midolman.FlowController.InvalidateFlowsByTag;
import org.midonet.client.dto.DtoRoute;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.client.resource.*;
import org.midonet.cluster.DataClient;
import org.midonet.client.MidonetApi;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.midolman.topology.VirtualTopologyActor;
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest;

import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.midonet.functional_test.PacketHelper.*;
import static org.junit.Assert.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ZookeeperDisconnectionTest {
    private final static Logger log = LoggerFactory.getLogger(ZookeeperDisconnectionTest.class);

    final String TENANT_NAME = "tenant-zk-disconnection-test";
    final String testConfigurationPath =
            "midolman_runtime_configurations/midolman-default.conf";

    Bridge bridgeA, bridgeB;
    Router router;
    Host thisHost;
    UUID thisHostId;

    BridgePort portA, portB;
    MAC macA = MAC.fromString("02:12:34:56:78:90");
    MAC macB = MAC.fromString("02:21:43:65:87:09");
    MAC rtrMacA = MAC.fromString("02:13:35:57:79:91");
    MAC rtrMacB = MAC.fromString("02:22:44:66:99:22");
    IPv4Addr ipA = IPv4Addr.fromString("192.168.1.1");
    IPv4Addr ipB = IPv4Addr.fromString("192.168.2.2");
    IPv4Subnet rtrIpA = new IPv4Subnet("192.168.1.254", 24);
    IPv4Subnet rtrIpB = new IPv4Subnet("192.168.2.254", 24);

    TapWrapper tapA;
    TapWrapper tapB;

    ApiServer apiStarter;
    MidonetApi apiClient;
    EmbeddedMidolman midolman;

    DataClient dataClient;

    int zkPort;

    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";

    @Before
    public void setUp() throws Exception {
        File testConfigFile = new File(testConfigurationPath);
        log.info("Starting embedded zookeper");
        zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new ApiServer(zkPort);
        apiClient = new MidonetApi(apiStarter.getURI());
        log.info("Starting midolman");
        midolman = startEmbeddedMidolman(testConfigFile.getAbsolutePath());
        dataClient = midolman.getDataClient();

        TestProbe probe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);

        log.info("Creating virtual topology");
        bridgeA = apiClient.addBridge().
                tenantId(TENANT_NAME).
                name("bridgeA").
                create();

        bridgeB = apiClient.addBridge().
                tenantId(TENANT_NAME).
                name("bridgeB").
                create();

        router = apiClient.addRouter().
                tenantId(TENANT_NAME).
                name("router1").
                create();

        RouterPort routerToA = router.addInteriorRouterPort().
                portAddress(rtrIpA.getAddress().toString()).
                networkAddress(rtrIpA.toNetworkAddress().toString()).
                networkLength(rtrIpA.getPrefixLen()).
                portMac(rtrMacA.toString()).
                create();

        RouterPort routerToB = router.addInteriorRouterPort().
                portAddress(rtrIpB.getAddress().toString()).
                networkAddress(rtrIpB.toNetworkAddress().toString()).
                networkLength(rtrIpB.getPrefixLen()).
                portMac(rtrMacB.toString()).
                create();

        router.addRoute().srcNetworkAddr("0.0.0.0").
                srcNetworkLength(0).
                dstNetworkAddr(routerToA.getNetworkAddress()).
                dstNetworkLength(routerToA.getNetworkLength()).
                nextHopPort(routerToA.getId()).
                type(DtoRoute.Normal).
                weight(10).
                create();
        router.addRoute().srcNetworkAddr("0.0.0.0").
                srcNetworkLength(0).
                dstNetworkAddr(routerToB.getNetworkAddress()).
                dstNetworkLength(routerToB.getNetworkLength()).
                nextHopPort(routerToB.getId()).
                type(DtoRoute.Normal).
                weight(10).
                create();

        BridgePort aToRouter = bridgeA.addInteriorPort().create();
        BridgePort bToRouter = bridgeB.addInteriorPort().create();

        routerToA.link(aToRouter.getId()).update();
        routerToB.link(bToRouter.getId()).update();

        portA = bridgeA.addExteriorPort().create();
        portB = bridgeB.addExteriorPort().create();

        ResourceCollection<Host> hosts = apiClient.getHosts();
        thisHost = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                thisHost = h;
                thisHostId = h.getId();
            }
        }
        // check that we've actually found the test host.
        assertNotNull(thisHost);

        tapA = new TapWrapper("TapA");
        thisHost.addHostInterfacePort().
                interfaceName(tapA.getName()).
                portId(portA.getId()).
                create();
        probe.expectMsgClass(Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);

        tapB = new TapWrapper("TapB");
        thisHost.addHostInterfacePort().
                interfaceName(tapB.getName()).
                portId(portB.getId()).
                create();
        probe.expectMsgClass(Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tapA);
        removeTapWrapper(tapB);
        unblockZkCommunications(zkPort);
        stopEmbeddedMidolman();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    private void icmpRoutedFromTapToTap(TapWrapper tapFrom, TapWrapper tapTo,
            MAC macFrom, MAC macTo, IPv4Addr ipFrom, IPv4Addr ipTo)
            throws Exception {
        byte[] pkt = makeIcmpEchoRequest(macFrom, ipFrom, macTo, ipTo);

        assertThat("The packet should have been sent from the source tap.",
            tapFrom.send(pkt));

        byte[] bytesOut = tapTo.recv();
        assertThat("The packet should have arrived at the destination tap.",
            bytesOut, allOf(notNullValue()));
        Ethernet pktOut = Ethernet.deserialize(bytesOut);
        assertThat("Received an IPv4 packet", pktOut.getPayload() instanceof IPv4);
        IPv4 ip = (IPv4) pktOut.getPayload();
        assertThat("Src address", ip.getSourceAddress() == ipFrom.addr());
        assertThat("Dst address", ip.getDestinationAddress() == ipTo.addr());
        assertThat("Is ICMP", ip.getPayload() instanceof ICMP);
        ICMP icmp = (ICMP) ip.getPayload();
        assertThat("Is echo", icmp.getType() == ICMP.TYPE_ECHO_REQUEST);
    }


    @Ignore
    @Test
    public void testSimulationBeforeAndAfterDisconnection() throws Exception {
        log.info("simulating a packet");
        arpAndCheckReply(tapB, macB, ipB, rtrIpB.getAddress(), rtrMacB);
        icmpRoutedFromTapToTap(tapA, tapB, macA, rtrMacA, ipA, ipB);

        log.info("blocking communications with zookeeper");
        assertThat("iptables command is successful", blockZkCommunications(zkPort) == 0);

        Waiters.sleepBecause("We want the ZK client to transiently disconnect", 16);

        log.info("turning communications with zookeeper back on");
        assertThat("iptables command is successful", unblockZkCommunications(zkPort) == 0);

        log.info("invalidating flows by tag: bridge id");
        TestProbe flowProbe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                flowProbe.ref(), WildcardFlowRemoved.class);
        midolman.getFlowController().tell(
                new InvalidateFlowsByTag(
                        FlowTagger.invalidateFlowsByDevice(bridgeB.getId())));
        flowProbe.expectMsgClass(Duration.create(10, TimeUnit.SECONDS),
                WildcardFlowRemoved.class);

        log.info("simulating a second packet");
        icmpRoutedFromTapToTap(tapA, tapB, macA, rtrMacA, ipA, ipB);
    }

    @Ignore
    @Test
    public void testSimulationDuringAndAfterDisconnection() throws Exception {
        log.info("blocking communications with zookeeper");
        assertThat("iptables command is successful", blockZkCommunications(zkPort) == 0);

        Waiters.sleepBecause("We want the ZK client to transiently disconnect", 16);

        log.info("simulating a packet");
        tapB.send(PacketHelper.makeArpRequest(macB, ipB, rtrIpB.getAddress()));
        Waiters.sleepBecause("We want the ZK requests to fail", 5);
        icmpFromTapDoesntArriveAtTap(tapA, tapB, macA, rtrMacA, ipA, ipB);
        Waiters.sleepBecause("We want the ZK requests to fail", 5);

        log.info("turning communications with zookeeper back on");
        assertThat("iptables command is successful", unblockZkCommunications(zkPort) == 0);

        log.info("draining tap...");
        for (byte[] pkt = tapB.recv(); pkt != null; pkt = tapB.recv()) {
            Ethernet eth = Ethernet.deserialize(pkt);
            log.info("ignoring packet: {}", eth.toString());
        }

        log.info("simulating an identical packet");
        Waiters.sleepBecause("We want the temporary drop flow to time out", 6);
        arpAndCheckReply(tapB, macB, ipB, rtrIpB.getAddress(), rtrMacB);
        icmpRoutedFromTapToTap(tapA, tapB, macA, rtrMacA, ipA, ipB);
    }

    @Test
    public void testDisconnection() throws Exception {
        log.info("blocking communications with zookeeper");
        assertThat("iptables command is successful", blockZkCommunications(zkPort) == 0);

        log.info("sending a router request to the VirtualTopologyActor");
        ActorRef vta = VirtualTopologyActor.getRef(midolman.getActorSystem());
        Future<Object> routerFuture =
            Patterns.ask(vta, new RouterRequest(router.getId(), true), 30000);
        Waiters.sleepBecause("We want the ZK request to fail", 16);

        assertThat("Router future has not completed", !routerFuture.isCompleted());

        log.info("turning communications with zookeeper back on");
        assertThat("iptables command is successful", unblockZkCommunications(zkPort) == 0);

        log.info("waiting for the RCU bridge to be sent to us");
        Object result = Await.result(routerFuture, Duration.parse("30 seconds"));
        assertThat("result is of Bridge type", result,
                   instanceOf(org.midonet.midolman.simulation.Router.class));
    }
}
