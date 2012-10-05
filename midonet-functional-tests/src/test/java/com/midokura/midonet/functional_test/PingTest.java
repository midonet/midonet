/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.testkit.TestProbe;
import akka.util.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.util.process.ProcessHelper.newProcess;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PingTest {

    private final static Logger log = LoggerFactory.getLogger(PingTest.class);

    IntIPv4 rtrIp1 = IntIPv4.fromString("192.168.111.1", 24);
    IntIPv4 rtrIp2 = IntIPv4.fromString("192.168.222.1", 24);
    IntIPv4 ip1 = IntIPv4.fromString("192.168.111.2", 24);
    IntIPv4 ip2 = IntIPv4.fromString("192.168.222.2", 24);
    final String TENANT_NAME = "tenant-ping";

    RouterPort<DtoMaterializedRouterPort> p1;
    RouterPort<DtoMaterializedRouterPort> p2;
    TapWrapper tap1;
    TapWrapper tap2;
    Set<UUID> activatedPorts = new HashSet<UUID>();
    PacketHelper helper1;
    MidolmanLauncher midolman;
    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;


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
        apiClient = new MidonetMgmt(apiStarter.getURI());

        log.info("Starting midolman");
        EmbeddedMidolman mm = startEmbeddedMidolman(testConfigurationPath);
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
            probe.ref(), LocalPortActive.class);

        log.debug("Building router");
        Router rtr = apiClient.addRouter().tenantId(TENANT_NAME)
            .name("rtr1").create();
        log.debug("Router done!: " + rtr.getName());
        p1 = rtr.addMaterializedRouterPort()
            .portAddress(rtrIp1.toUnicastString())
            .networkAddress(rtrIp1.toNetworkAddress().toUnicastString())
            .networkLength(rtrIp1.getMaskLength())
            .localNetworkAddress(rtrIp1.toNetworkAddress().toUnicastString())
            .localNetworkLength(rtrIp1.getMaskLength())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrIp1.toNetworkAddress().toUnicastString())
            .dstNetworkLength(rtrIp1.getMaskLength())
            .nextHopPort(p1.getId()).type(DtoRoute.Normal).weight(10).create();
        p2 = rtr.addMaterializedRouterPort()
            .portAddress(rtrIp2.toUnicastString())
            .networkAddress(rtrIp2.toNetworkAddress().toUnicastString())
            .networkLength(rtrIp2.getMaskLength())
            .localNetworkAddress(rtrIp2.toNetworkAddress().toUnicastString())
            .localNetworkLength(rtrIp2.getMaskLength())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrIp2.toNetworkAddress().toUnicastString())
            .dstNetworkLength(rtrIp2.getMaskLength())
            .nextHopPort(p2.getId()).type(DtoRoute.Normal).weight(10).create();

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
        tap1 = new TapWrapper("tapPing1");

        log.debug("Adding interface to host.");
        host.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(p1.getId()).create();

        // Bind the internal 'local' port to the second vport.
        String localName = "midonet";
        host.addHostInterfacePort()
            .interfaceName(localName)
            .portId(p2.getId()).create();

        for (int i = 0; i < 2; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());
        }
        assertThat("The 2 router ports should be active.", activatedPorts,
            hasItems(p1.getId(), p2.getId()));

        newProcess(
            String.format("sudo -n ip link set dev %s arp on " +
                "mtu 1400 multicast off up", localName))
            .logOutput(log, "int_port")
            .runAndWait();

        newProcess(
            String.format("sudo -n ip addr add %s/%d dev %s",
                ip2.toUnicastString(), ip2.getMaskLength(), localName))
            .logOutput(log, "int_port")
            .runAndWait();

        newProcess(
            String.format("sudo -n ip route add %s/%d via %s",
                ip1.toNetworkAddress().toUnicastString(),
                ip1.getMaskLength(), rtrIp2.toUnicastString()))
            .logOutput(log, "int_port")
            .runAndWait();

        helper1 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:01"), ip1,
            rtrIp1);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(tap1);
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void testArpResolutionAndPortPing()
            throws MalformedPacketException, InterruptedException {
        byte[] request;

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                tap1.send(helper1.makeArpRequest()));

        MAC rtrMac = helper1.checkArpReply(tap1.recv());
        helper1.setGwMac(rtrMac);

        // Ping near router port.
        request = helper1.makeIcmpEchoRequest(rtrIp1);
        assertThat(String.format("The tap %s should have sent the packet",
            tap1.getName()), tap1.send(request));

        // The router does not ARP before delivering the echo reply because
        // our ARP request seeded the ARP table.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // Ping far router port.
        request = helper1.makeIcmpEchoRequest(rtrIp2);
        assertThat(String.format("The tap %s should have sent the packet",
            tap1.getName()), tap1.send(request));

        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // Ping internal port p3.
        request = helper1.makeIcmpEchoRequest(ip2);
        assertThat("The tap should have sent the packet again",
                tap1.send(request));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        assertNoMorePacketsOnTap(tap1);
    }
}