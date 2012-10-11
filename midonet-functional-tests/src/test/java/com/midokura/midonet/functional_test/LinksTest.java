/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import akka.testkit.TestProbe;
import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.*;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.util.process.ProcessHelper.newProcess;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;

public class LinksTest {

    private final static Logger log = LoggerFactory.getLogger(LinksTest.class);

    IntIPv4 rtrIp1 = IntIPv4.fromString("192.168.111.1", 24);
    IntIPv4 rtrIp2 = IntIPv4.fromString("192.168.222.1", 24);
    IntIPv4 vm1IP = IntIPv4.fromString("192.168.111.2", 24);
    IntIPv4 vm2IP = IntIPv4.fromString("192.168.222.2", 24);
    MAC vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03");
    final String TENANT_NAME = "tenant-link";

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
            "midolmanj_runtime_configurations/midolman-link.conf";

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
            .name("link_br").create();
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
        tap1 = new TapWrapper("tapLink1");

        log.debug("Bind tap to router's materialized port.");
        host.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(rtrPort1.getId()).create();

        // Bind the internal 'local' port to the second materialized port.
        String localName = "midonet_link";
        log.debug("Bind datapath's local port to bridge's materialized port.");
        host.addHostInterfacePort()
            .interfaceName(localName)
            .portId(brPort2.getId()).create();

        /*for (int i = 0; i < 2; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());
        } */

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

    }

    @After
    public void tearDown() throws Exception {

        log.info("Stopping Midolman");
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void testArpResolutionAndPortPing()
            throws MalformedPacketException, InterruptedException {

        TimeUnit.SECONDS.sleep(10);
        log.info("Removing TAP");
        removeTapWrapper(tap1);
        TimeUnit.SECONDS.sleep(10);
    }
}