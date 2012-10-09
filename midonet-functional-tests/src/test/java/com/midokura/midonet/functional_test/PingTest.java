/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoDhcpOption121;
import com.midokura.midonet.client.dto.DtoLogicalBridgePort;
import com.midokura.midonet.client.dto.DtoLogicalRouterPort;
import com.midokura.midonet.client.dto.DtoMaterializedRouterPort;
import com.midokura.midonet.client.dto.DtoRoute;
import com.midokura.midonet.client.resource.Bridge;
import com.midokura.midonet.client.resource.BridgePort;
import com.midokura.midonet.client.resource.DhcpSubnet;
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
    IntIPv4 vm1IP = IntIPv4.fromString("192.168.111.2", 24);
    IntIPv4 vm2IP = IntIPv4.fromString("192.168.222.2", 24);
    MAC vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03");
    final String TENANT_NAME = "tenant-ping";

    RouterPort<DtoMaterializedRouterPort> rtrPort1;
    RouterPort<DtoLogicalRouterPort> rtrPort2;
    BridgePort<DtoLogicalBridgePort> brPort1;
    BridgePort<DtoBridgePort> brPort2;
    TapWrapper tap1;
    Set<UUID> activatedPorts = new HashSet<UUID>();
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

        // TODO(pino): delete the datapath before starting MM
        log.info("Starting midolman");
        EmbeddedMidolman mm = startEmbeddedMidolman(testConfigurationPath);
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
            probe.ref(), LocalPortActive.class);

        // Build a router
        Router rtr = apiClient.addRouter().tenantId(TENANT_NAME)
            .name("rtr1").create();
        // Add a materialized port.
        rtrPort1 = rtr.addMaterializedRouterPort()
            .portAddress(rtrIp1.toUnicastString())
            .networkAddress(rtrIp1.toNetworkAddress().toUnicastString())
            .networkLength(rtrIp1.getMaskLength())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort1.getNetworkAddress())
            .dstNetworkLength(rtrPort1.getNetworkLength())
            .nextHopPort(rtrPort1.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        // Add a logical port to the router.
        rtrPort2 = rtr.addLogicalRouterPort()
            .portAddress(rtrIp2.toUnicastString())
            .networkAddress(rtrIp2.toNetworkAddress().toUnicastString())
            .networkLength(rtrIp2.getMaskLength())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort2.getNetworkAddress())
            .dstNetworkLength(rtrPort2.getNetworkLength())
            .nextHopPort(rtrPort2.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        // Build a bridge and link it to the router's logical port
        Bridge br = apiClient.addBridge().tenantId(TENANT_NAME)
            .name("br").create();
        brPort1 = br.addLogicalPort().create();
        // Link the bridge to the router
        rtrPort2.link(brPort1.getId());

        // Add a materialized port on the bridge.
        brPort2 = br.addMaterializedPort().create();

        // Add a DHCP static assignment for the VM on the bridge (vm2).
        // We need a DHCP option 121 fr a static route to the other VM (vm1).
        List<DtoDhcpOption121> opt121Routes =
            new ArrayList<DtoDhcpOption121>();
        opt121Routes.add(new DtoDhcpOption121(
            rtrPort1.getNetworkAddress(), rtrPort1.getNetworkLength(),
            rtrPort2.getPortAddress()));
        DhcpSubnet dhcpSubnet = br.addDhcpSubnet()
            .defaultGateway(rtrPort2.getPortAddress())
            .subnetPrefix(rtrPort2.getNetworkAddress())
            .subnetLength(rtrPort2.getNetworkLength())
            .opt121Routes(opt121Routes)
            .create();
        dhcpSubnet.addDhcpHost()
            .ipAddr(vm2IP.toUnicastString())
            .macAddr(vm2Mac.toString())
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
        tap1 = new TapWrapper("tapPing1");

        log.debug("Bind tap to router's materialized port.");
        host.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(rtrPort1.getId()).create();

        // Bind the internal 'local' port to the second materialized port.
        String localName = "midonet";
        log.debug("Bind datapath's local port to bridge's materialized port.");
        host.addHostInterfacePort()
            .interfaceName(localName)
            .portId(brPort2.getId()).create();

        for (int i = 0; i < 2; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());
        }
        assertThat("The 2 router ports should be active.", activatedPorts,
            hasItems(rtrPort1.getId(), brPort2.getId()));

        // Set the datapath's local port to up and set its MAC address.
        newProcess(
            String.format("sudo -n ip link set dev %s address %s arp on " +
                "mtu 1400 multicast off", localName, vm2Mac.toString()))
            .logOutput(log, "int_port")
            .runAndWait();

        // Now ifup the local port to run the DHCP client.
        newProcess(
            String.format("sudo ifdown %s --interfaces " +
                "./midolmanj_runtime_configurations/pingtest.network",
                localName))
            .logOutput(log, "int_port")
            .runAndWait();
        newProcess(
            String.format("sudo ifup %s --interfaces " +
                "./midolmanj_runtime_configurations/pingtest.network",
                localName))
            .logOutput(log, "int_port")
            .runAndWait();

        // No need to set the IP address (and static route to VM1) for the
        // datapath's local port. They're set via DHCP. Otherwise,
        // here's what we'd do.
        /* newProcess(
            String.format("sudo -n ip addr add %s/%d dev %s",
                vm2IP.toUnicastString(), vm2IP.getMaskLength(), localName))
            .logOutput(log, "int_port")
            .runAndWait();

        newProcess(
            String.format("sudo -n ip route add %s/%d via %s",
                vm1IP.toNetworkAddress().toUnicastString(),
                vm1IP.getMaskLength(), rtrIp2.toUnicastString()))
            .logOutput(log, "int_port")
            .runAndWait();
        */
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
        PacketHelper helper1 = new PacketHelper(
            MAC.fromString("02:00:00:aa:aa:01"), vm1IP, rtrIp1);
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
        request = helper1.makeIcmpEchoRequest(vm2IP);
        assertThat("The tap should have sent the packet again",
                tap1.send(request));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        assertNoMorePacketsOnTap(tap1);
    }
}