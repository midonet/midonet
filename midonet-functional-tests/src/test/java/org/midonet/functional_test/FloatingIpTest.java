/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.testkit.TestProbe;
import akka.util.Duration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.client.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.client.MidonetApi;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.DhcpSubnet;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.RouterPort;
import org.midonet.client.resource.RuleChain;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.ICMP;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.util.lock.LockHelper;
import org.midonet.util.process.ProcessHelper;


import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.midonet.util.process.ProcessHelper.newProcess;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FloatingIpTest {

    private final static Logger log = LoggerFactory
        .getLogger(FloatingIpTest.class);

    IPv4Subnet rtrIp1 = new IPv4Subnet("192.168.55.1", 24);
    IPv4Subnet rtrIp2 = new IPv4Subnet("192.168.66.1", 24);
    IPv4Subnet rtrIp3 = new IPv4Subnet("192.168.77.1", 24);
    IPv4Subnet vm1IP = new IPv4Subnet("192.168.55.2", 24);
    IPv4Subnet vm2IP = new IPv4Subnet("192.168.66.2", 24);
    IPv4Subnet vm3IP = new IPv4Subnet("192.168.77.2", 24);
    MAC vm3Mac = MAC.fromString("02:DD:AA:DD:AA:03");
    IPv4Addr floatingIP = IPv4Addr.fromString("10.0.173.5");

    TapWrapper tap1;
    TapWrapper tap2;
    ApiServer apiStarter;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID =
        "910de343-c39b-4933-86c7-540225fb02f9";

    @BeforeClass
    public static void checkLock() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);
    }

    @AfterClass
    public static void releaseLock() {
        lock.release();
    }

    @Before
    public void setUp() throws InterruptedException, IOException {
        String testConfigurationPath =
            "midolman_runtime_configurations/midolman-default.conf";

        // start zookeeper with the designated port.
        log.info("Starting embedded zookeeper.");
        int zookeeperPort = startEmbeddedZookeeper(testConfigurationPath);
        Assert.assertThat(zookeeperPort, greaterThan(0));

        log.info("Starting cassandra");
        startCassandra();

        log.info("Starting REST API");
        apiStarter = new ApiServer(zookeeperPort);
        MidonetApi apiClient = new MidonetApi(apiStarter.getURI());

        // TODO(pino): delete the datapath before starting MM
        log.info("Starting midolman");
        EmbeddedMidolman mm = startEmbeddedMidolman(testConfigurationPath);
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
            probe.ref(), LocalPortActive.class);

        // Build a router
        org.midonet.client.resource.Router rtr =
            apiClient.addRouter().tenantId("fl_ip_tnt").name("rtr1").create();

        RouterPort rtrPort1 = rtr
            .addPort()
            .portAddress(rtrIp1.toUnicastString())
            .networkAddress(rtrIp1.toNetworkAddress().toString())
            .networkLength(rtrIp1.getPrefixLen())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort1.getNetworkAddress())
            .dstNetworkLength(rtrPort1.getNetworkLength())
            .nextHopPort(rtrPort1.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        RouterPort rtrPort2 = rtr
            .addPort()
            .portAddress(rtrIp2.toUnicastString())
            .networkAddress(rtrIp2.toNetworkAddress().toString())
            .networkLength(rtrIp2.getPrefixLen())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort2.getNetworkAddress())
            .dstNetworkLength(rtrPort2.getNetworkLength())
            .nextHopPort(rtrPort2.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        RouterPort rtrPort3 = rtr
            .addPort()
            .portAddress(rtrIp3.toUnicastString())
            .networkAddress(rtrIp3.toNetworkAddress().toString())
            .networkLength(rtrIp3.getPrefixLen())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort3.getNetworkAddress())
            .dstNetworkLength(rtrPort3.getNetworkLength())
            .nextHopPort(rtrPort3.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        // Build a bridge and link it to the router's interior port
        Bridge br = apiClient.addBridge().tenantId("fl_ip_tnt")
            .name("br").create();
        BridgePort brPort1 =
            br.addPort().create();
        // Link the bridge to the router's third (interior) port.
        rtrPort3.link(brPort1.getId());

        // Add a exterior port on the bridge.
        BridgePort brPort2 = br.addPort().create();

        // Add a DHCP static assignment for the VM on the bridge (vm3).
        // We need a DHCP option 121 fr a static route to the other VMs.
        List<DtoDhcpOption121> opt121Routes =
            new ArrayList<DtoDhcpOption121>();
        opt121Routes.add(new DtoDhcpOption121(
            rtrPort1.getNetworkAddress(), rtrPort1.getNetworkLength(),
            rtrPort3.getPortAddress()));
        opt121Routes.add(new DtoDhcpOption121(
            rtrPort2.getNetworkAddress(), rtrPort2.getNetworkLength(),
            rtrPort3.getPortAddress()));
        List<String> dnsServers = new ArrayList<String>();
        dnsServers.add("192.168.77.118");
        dnsServers.add("192.168.77.119");
        dnsServers.add("192.168.77.120");
        DhcpSubnet dhcpSubnet = br.addDhcpSubnet()
            .defaultGateway(rtrPort3.getPortAddress())
            .subnetPrefix(rtrPort3.getNetworkAddress())
            .subnetLength(rtrPort3.getNetworkLength())
            .serverAddr("192.168.77.118")
            // don't, this ends up breaking the dns on Jenkins
            //.dnsServerAddrs(dnsServers)
            .interfaceMTU(1400)
            .opt121Routes(opt121Routes)
            .create();
        dhcpSubnet.addDhcpHost()
            .ipAddr(vm3IP.toUnicastString())
            .macAddr(vm3Mac.toString())
            .create();

        RuleChain pre = apiClient.addChain().name("pre-routing")
            .tenantId("fl_ip_tnt").create();
        RuleChain post = apiClient.addChain().name("post-routing")
            .tenantId("fl_ip_tnt").create();
        rtr.inboundFilterId(pre.getId()).outboundFilterId(post.getId())
            .update();
        // Assign the floating IP to vm3's private address.
        // Treat rtrPort1 as the uplink: only packets transiting via rtrPort1
        // will have the floatingIP applied.
        pre.addRule().type(DtoRule.DNAT).flowAction(DtoRule.Accept)
            .nwDstAddress(floatingIP.toString()).nwDstLength(32)
            .inPorts(new UUID[] {rtrPort1.getId()})
            .natTargets(
                new DtoRule.DtoNatTarget[]{
                    new DtoRule.DtoNatTarget(vm3IP.toUnicastString(),
                        vm3IP.toUnicastString(), 0, 0)
                }).create();
        post.addRule().type(DtoRule.SNAT).flowAction(DtoRule.Accept)
            .nwSrcAddress(vm3IP.toUnicastString()).nwSrcLength(32)
            .outPorts(new UUID[] {rtrPort1.getId()})
            .natTargets(
                new DtoRule.DtoNatTarget[]{
                    new DtoRule.DtoNatTarget(floatingIP.toString(),
                        floatingIP.toString(), 0, 0)
                }).create();

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

        // Bind a tap to the router's first exterior port.
        tap1 = new TapWrapper("tapFL1");
        host.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(rtrPort1.getId()).create();

        // Bind a tap to the router's second exterior port.
        tap2 = new TapWrapper("tapFL2");
        host.addHostInterfacePort()
            .interfaceName(tap2.getName())
            .portId(rtrPort2.getId()).create();

        // Bind the internal 'local' port to the bridge's second
        // (exterior) port.
        String localName = "midonet";
        host.addHostInterfacePort()
            .interfaceName(localName)
            .portId(brPort2.getId()).create();

        // Set the datapath's local port to up and set its MAC address.
        newProcess(
            String.format("sudo -n ip link set dev %s address %s arp on " +
                              "mtu 1400 multicast off", localName, vm3Mac.toString()))
            .logOutput(log, "int_port")
            .runAndWait();

        // Now ifup the local port to run the DHCP client.
        newProcess(
            String.format("sudo ifdown %s --interfaces " +
                              "./midolman_runtime_configurations/pingtest.network",
                          localName))
            .logOutput(log, "int_port")
            .runAndWait();
        newProcess(
            String.format("sudo ifup %s --interfaces " +
                              "./midolman_runtime_configurations/pingtest.network",
                          localName))
            .logOutput(log, "int_port")
            .runAndWait();


        log.info("Waiting for LocalPortActive notifications for ports {}, {}," +
            " and {}",
            new Object[] {rtrPort1.getId(), rtrPort2.getId(), brPort2.getId()});

        Set<UUID> activatedPorts = new HashSet<UUID>();
        for (int i = 0; i < 3; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received a LocalPortActive message about {}.",
                activeMsg.portID());
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());
        }
        assertThat("The 3 exterior ports should be active.",
            activatedPorts,
            hasItems(rtrPort1.getId(), rtrPort2.getId(), brPort2.getId()));

        // No need to set the IP address (and static route to VM1) for the
        // datapath's local port. They're set via DHCP.
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        stopEmbeddedMidolman();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void testFloatingIp() throws MalformedPacketException {
        byte[] request;
        PacketHelper helper1 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:01"),
                vm1IP.getAddress(), rtrIp1.getAddress());
        PacketHelper helper2 = new PacketHelper(
                MAC.fromString("02:00:00:aa:aa:02"),
                vm2IP.getAddress(), rtrIp2.getAddress());

        // VM1 arps its near router port.
        assertThat("The ARP request was sent properly",
                tap1.send(helper1.makeArpRequest()));
        MAC rtrMac = helper1.checkArpReply(tap1.recv());
        // Now set the GW MAC so that other packets can be generated.
        helper1.setGwMac(rtrMac);

        // VM1 pings the far router port - to VM3
        request = helper1.makeIcmpEchoRequest(rtrIp3.getAddress());
        assertThat(String.format("The tap %s should have sent the packet",
                                 tap1.getName()), tap1.send(request));
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // VM1 sends ICMP echo request to the floatingIP.
        request = helper1.makeIcmpEchoRequest(floatingIP);
        assertTrue("The ICMP request was sent successfully",
            tap1.send(request));
        // Now the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // VM1 sends ICMP echo request to VM3's private IP.
        request = helper1.makeIcmpEchoRequest(vm3IP.getAddress());
        assertTrue("The ICMP request was sent successfully",
            tap1.send(request));
        // The ICMP reply is from the floatingIP not VM3's address.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv(), floatingIP);

        // VM2 arps its near router port.
        assertThat("The ARP request was sent properly",
            tap2.send(helper2.makeArpRequest()));
        rtrMac = helper2.checkArpReply(tap2.recv());
        // Now set the GW MAC so that other packets can be generated.
        helper2.setGwMac(rtrMac);

        // VM2 sends ICMP echo request to VM3's private IP.
        request = helper2.makeIcmpEchoRequest(vm3IP.getAddress());
        assertTrue(tap2.send(request));
        // The icmp echo reply is from the private address.
        PacketHelper.checkIcmpEchoReply(request, tap2.recv());

        // VM2 sends ICMP echo request to the floating IP.
        request = helper2.makeIcmpEchoRequest(floatingIP);
        assertTrue(tap2.send(request));
        // The floatingIP is not translated for packets ingressing router
        // ports other than rtrPort1. This one ingresses rtrPort2, isn't
        // translated, and since there's no route to the floatingIP
        // the router returns an ICMP !N.
        helper2.checkIcmpError(tap2.recv(), ICMP.UNREACH_CODE.UNREACH_NET,
                               rtrIp2.getAddress(), request);

        // No other packets arrive at the tap ports.
        assertNoMorePacketsOnTap(tap1);
        assertNoMorePacketsOnTap(tap2);

        // after the test, check all the parameters for DHCP
        ProcessHelper.ProcessResult result;
        String midnetDhcpFile = "/var/lib/dhcp/dhclient.midonet.leases";
        if (new File(midnetDhcpFile).exists()) {
            String entryCmd = "grep -w midonet " + midnetDhcpFile;
            result = ProcessHelper.executeCommandLine(entryCmd);
            int numLines = result.consoleOutput.size() - 1;
            String serverCmd =
                "grep -w dhcp-server-identifier " + midnetDhcpFile;
            result = ProcessHelper.executeCommandLine(serverCmd);
            String output = result.consoleOutput.get(numLines);
            log.debug("Server ID from DHCP file is {}", output);
            if (! output.contains("192.168.77.118")) {
                log.error("Server ID does NOT match: {} != {}",
                          "192.168.77.118", output);
            }
            /*
            serverCmd = "grep -w domain-name-servers " + midnetDhcpFile;
            result = ProcessHelper.executeCommandLine(serverCmd);
            numLines = result.consoleOutput.size() - 1;
            output = result.consoleOutput.get(numLines);
            log.debug("DNS Server ID from DHCP file is {}", output);
            if (! output.contains("192.168.77.128")) {
                log.error("DNS Server ID does NOT match: {} != {}",
                          "192.168.77.128", output);
            }
            */
            serverCmd = "grep -w interface-mtu " + midnetDhcpFile;
            result = ProcessHelper.executeCommandLine(serverCmd);
            output = result.consoleOutput.get(numLines);
            log.debug("Interface MTU from DHCP file is {}", output);
            if (! output.contains("1400")) {
                log.error("Interface MTU does NOT match: {} != {}",
                          "1400", output);
            }
        } else {
            // TODO solve this - the functional tests should not depend on the
            // integration layer to work.
            log.error("File not found: {}", midnetDhcpFile);
            log.error("This indicates that the midonet interface is not " +
                      "DHCP configured");
        }

    }
}
