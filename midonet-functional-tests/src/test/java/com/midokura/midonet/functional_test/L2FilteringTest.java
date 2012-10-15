/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.HashSet;
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

import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoLogicalBridgePort;
import com.midokura.midonet.client.dto.DtoLogicalRouterPort;
import com.midokura.midonet.client.dto.DtoRoute;
import com.midokura.midonet.client.dto.DtoRule;
import com.midokura.midonet.client.resource.Bridge;
import com.midokura.midonet.client.resource.BridgePort;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midonet.client.resource.RouterPort;
import com.midokura.midonet.client.resource.Rule;
import com.midokura.midonet.client.resource.RuleChain;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IPv4;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.LLDP;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.util.Waiters.sleepBecause;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class L2FilteringTest {
    IntIPv4 rtrIp = IntIPv4.fromString("10.0.0.254", 24);
    RouterPort<DtoLogicalRouterPort> rtrPort;
    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    Bridge bridge;
    BridgePort<DtoBridgePort> brPort3;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;
    TapWrapper tap4;
    TapWrapper tap5;

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
    public void setUp() throws IOException, InterruptedException {
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

        // TODO(pino): delete the datapath before starting MM
        log.info("Starting midolman");
        EmbeddedMidolman mm = startEmbeddedMidolman(testConfigurationPath);
        TestProbe probe = new TestProbe(mm.getActorSystem());
        mm.getActorSystem().eventStream().subscribe(
            probe.ref(), LocalPortActive.class);

        // Build a router
        Router rtr =
            apiClient.addRouter().tenantId("L2filter_tnt").name("rtr1").create();
        // Add a logical port to the router.
        rtrPort = rtr
            .addLogicalRouterPort()
            .portAddress(rtrIp.toUnicastString())
            .networkAddress(rtrIp.toNetworkAddress().toUnicastString())
            .networkLength(rtrIp.getMaskLength())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort.getNetworkAddress())
            .dstNetworkLength(rtrPort.getNetworkLength())
            .nextHopPort(rtrPort.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        // Build a bridge
        bridge = apiClient.addBridge()
            .tenantId("L2filter_tnt").name("br").create();
        // Link the bridge to the router.
        BridgePort<DtoLogicalBridgePort> logBrPort =
            bridge.addLogicalPort().create();
        rtrPort.link(logBrPort.getId());

        tap1 = new TapWrapper("l2filterTap1");
        tap2 = new TapWrapper("l2filterTap2");
        tap3 = new TapWrapper("l2filterTap3");
        tap4 = new TapWrapper("l2filterTap4");
        tap5 = new TapWrapper("l2filterTap5");

        // Now bind the taps to materialized bridge ports.
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

        host.addHostInterfacePort().interfaceName(tap1.getName())
            .portId(bridge.addMaterializedPort().create().getId()).create();
        host.addHostInterfacePort().interfaceName(tap2.getName())
            .portId(bridge.addMaterializedPort().create().getId()).create();
        brPort3 = bridge.addMaterializedPort().create();
        host.addHostInterfacePort().interfaceName(tap3.getName())
            .portId(brPort3.getId()).create();
        host.addHostInterfacePort().interfaceName(tap4.getName())
            .portId(bridge.addMaterializedPort().create().getId()).create();
        host.addHostInterfacePort().interfaceName(tap5.getName())
            .portId(bridge.addMaterializedPort().create().getId()).create();

        log.info("Waiting for 5 LocalPortActive notifications");
        Set<UUID> activatedPorts = new HashSet<UUID>();
        for (int i = 0; i < 5; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received a LocalPortActive message about {}.",
                activeMsg.portID());
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());
        }
        assertThat("The 5 materialized ports should be active.",
            activatedPorts, hasSize(5));
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
        removeTapWrapper(tap4);
        removeTapWrapper(tap5);
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void test() throws MalformedPacketException, InterruptedException {
        MAC mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        MAC mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        MAC mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        MAC mac4 = MAC.fromString("02:aa:bb:cc:dd:d4");
        MAC mac5 = MAC.fromString("02:aa:bb:cc:dd:d5");
        IntIPv4 ip1 = IntIPv4.fromString("10.0.0.1");
        IntIPv4 ip2 = IntIPv4.fromString("10.0.0.2");
        IntIPv4 ip3 = IntIPv4.fromString("10.0.0.3");
        IntIPv4 ip4 = IntIPv4.fromString("10.0.0.4");
        IntIPv4 ip5 = IntIPv4.fromString("10.0.0.5");

        // Send ARPs from each edge port so that the bridge can learn MACs.
        MAC rtrMac = MAC.fromString(rtrPort.getPortMac());
        arpAndCheckReply(tap1, mac1, ip1, rtrIp, rtrMac);
        arpAndCheckReply(tap2, mac2, ip2, rtrIp, rtrMac);
        arpAndCheckReply(tap3, mac3, ip3, rtrIp, rtrMac);
        arpAndCheckReply(tap4, mac4, ip4, rtrIp, rtrMac);
        arpAndCheckReply(tap5, mac5, ip5, rtrIp, rtrMac);

        // All traffic is allowed now.
        icmpFromTapArrivesAtTap(tap1, tap2, mac1, mac2, ip1, ip2);
        icmpFromTapArrivesAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        icmpFromTapArrivesAtTap(tap1, tap4, mac1, mac4, ip1, ip4);
        icmpFromTapArrivesAtTap(tap1, tap5, mac1, mac5, ip1, ip5);

        // And specifically this traffic - which we'll block very soon.
        // ip4 to ip1
        icmpFromTapArrivesAtTap(tap4, tap1, mac4, mac1, ip4, ip1);
        icmpFromTapArrivesAtTap(tap4, tap2, mac4, mac2, ip4, ip2);
        // mac5 to mac2
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip5, ip2);
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip4, ip2);
        // LLDP packets.
        lldpFromTapArrivesAtTap(tap3, tap2, mac3, mac2);
        lldpFromTapArrivesAtTap(tap2, tap1, mac2, mac1);
        lldpFromTapArrivesAtTap(tap5, tap4, mac5, mac4);

        // Now create a chain for the L2 virtual bridge's inbound filter.
        RuleChain brInFilter = apiClient.addChain()
            .name("brInFilter").tenantId("pgroup_tnt").create();
        // Add a rule that drops packets from ip4 to ip1.
        Rule rule1 = brInFilter.addRule().type(DtoRule.Drop)
            .nwSrcAddress(ip4.toUnicastString()).nwSrcLength(32)
            .nwDstAddress(ip1.toUnicastString()).nwDstLength(32)
            .create();
        // Add a rule that drops packets from mac5 to mac2.
        Rule rule2 = brInFilter.addRule().type(DtoRule.Drop)
            .dlSrc(mac5.toString()).dlDst(mac2.toString())
            .create();
        // Add a rule that drops LLDP packets.
        Rule rule3 = brInFilter.addRule().type(DtoRule.Drop)
            .dlType(LLDP.ETHERTYPE).create();

        // Set this chain as the bridge's inbound filter.
        bridge.inboundFilterId(brInFilter.getId()).update();
        sleepBecause("we need the network to process the new filter", 2);

        // ip4 cannot send packets to ip1
        icmpFromTapDoesntArriveAtTap(tap4, tap1, mac4, mac1, ip4, ip1);
        icmpFromTapDoesntArriveAtTap(tap4, tap2, mac4, mac2, ip4, ip1);

        // mac5 cannot send packets to mac2
        icmpFromTapDoesntArriveAtTap(tap5, tap2, mac5, mac2, ip5, ip2);
        icmpFromTapDoesntArriveAtTap(tap5, tap2, mac5, mac2, ip4, ip2);

        // No one can send LLDP packets.
        lldpFromTapDoesntArriveAtTap(tap3, tap2, mac3, mac2);
        lldpFromTapDoesntArriveAtTap(tap1, tap2, mac1, mac2);
        lldpFromTapDoesntArriveAtTap(tap5, tap4, mac5, mac4);

        // Now remove the previous rules.
        rule1.delete();
        rule2.delete();
        rule3.delete();
        // Add a rule the drops any packet from mac1.
        brInFilter.addRule().type(DtoRule.Drop)
            .dlSrc(mac1.toString()).create();
        // Add a rule that drops any IP packet that ingresses the third port.
        brInFilter.addRule().type(DtoRule.Drop)
            .inPorts(new UUID[] {brPort3.getId()})
            .dlType(IPv4.ETHERTYPE).create();
        sleepBecause("we need the network to process the rule changes", 2);

        // The traffic that was blocked now passes:
        // ip4 to ip1
        icmpFromTapArrivesAtTap(tap4, tap1, mac4, mac1, ip4, ip1);
        icmpFromTapArrivesAtTap(tap4, tap2, mac4, mac2, ip4, ip2);
        // mac5 to mac2
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip5, ip2);
        icmpFromTapArrivesAtTap(tap5, tap2, mac5, mac2, ip4, ip2);
        lldpFromTapArrivesAtTap(tap5, tap2, mac5, mac2);
        // LLDP packets
        lldpFromTapArrivesAtTap(tap3, tap2, mac3, mac2);
        lldpFromTapArrivesAtTap(tap2, tap1, mac2, mac1);
        lldpFromTapArrivesAtTap(tap5, tap4, mac5, mac4);

        // ICMPs from mac1 should now be dropped.
        icmpFromTapDoesntArriveAtTap(tap1, tap2, mac1, mac2, ip1, ip2);
        icmpFromTapDoesntArriveAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        // LLDP from mac1 should also be dropped.
        lldpFromTapDoesntArriveAtTap(tap1, tap4, mac1, mac4);
        lldpFromTapDoesntArriveAtTap(tap1, tap5, mac1, mac5);
        // ARPs from mac1 will also be dropped.
        assertThat("The ARP request should have been sent.",
            tap1.send(PacketHelper.makeArpRequest(mac1, ip1, rtrIp)));
        assertThat("No ARP reply since the request should not have arrived.",
            tap1.recv(), nullValue());
        // Other ARPs pass and are correctly resolved.
        arpAndCheckReply(tap2, mac2, ip2, rtrIp, rtrMac);
        arpAndCheckReply(tap4, mac4, ip4, rtrIp, rtrMac);

        // ICMPs ingressing on tap3 should now be dropped.
        icmpFromTapDoesntArriveAtTap(tap3, tap2, mac3, mac2, ip3, ip2);
        icmpFromTapDoesntArriveAtTap(tap3, tap4, mac3, mac4, ip3, ip4);
        icmpFromTapDoesntArriveAtTap(tap3, tap5, mac3, mac5, ip3, ip5);
        // But ARPs ingressing on tap3 may pass.
        arpAndCheckReply(tap3, mac3, ip3, rtrIp, rtrMac);
        // And LLDPs ingressing tap3 also pass.
        lldpFromTapArrivesAtTap(tap3, tap4, mac3, mac4);
        lldpFromTapArrivesAtTap(tap3, tap5, mac3, mac5);

        // Finally, remove bridge1's inboundFilter.
        bridge.inboundFilterId(null).update();
        sleepBecause("we need the network to process the filter changes", 2);

        // ICMPs from mac1 are again delivered
        icmpFromTapArrivesAtTap(tap1, tap2, mac1, mac2, ip1, ip2);
        icmpFromTapArrivesAtTap(tap1, tap3, mac1, mac3, ip1, ip3);
        // LLDP from mac1 are again passing.
        lldpFromTapArrivesAtTap(tap1, tap4, mac1, mac4);
        lldpFromTapArrivesAtTap(tap1, tap5, mac1, mac5);
        // ARPs ingressing on tap1 are again delivered
        arpAndCheckReply(tap1, mac1, ip1, rtrIp, rtrMac);

        // ICMPs ingressing on tap3 again pass.
        icmpFromTapArrivesAtTap(tap3, tap2, mac3, mac2, ip3, ip2);
        icmpFromTapArrivesAtTap(tap3, tap4, mac3, mac4, ip3, ip4);
        icmpFromTapArrivesAtTap(tap3, tap5, mac3, mac5, ip3, ip5);

        // TODO(pino): Add a Rule that drops IPv6 packets (Ethertype 0x86DD)
        // TODO:       Show that IPv6 is forwarded before the rule is installed,
        // TODO:       and dropped afterwards. This will also check that using
        // TODO:       (signed) Short for dlType is correctly handled by Java.
    }
}
