/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

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
import org.junit.Ignore;
import org.junit.Test;

import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoInteriorBridgePort;
import org.midonet.client.dto.DtoInteriorRouterPort;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.PortGroup;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.client.resource.RuleChain;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.ARP;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.Unsigned;
import org.midonet.util.lock.LockHelper;


import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * This class emulates a MidoNet client implementing SecurityGroups using
 * MidoNet PortGroups to track/match SecurityGroup membership.
 */
@Ignore
public class PortGroupTest {
    IPv4Subnet rtrIp = new IPv4Subnet("10.0.0.254", 24);
    RouterPort<DtoInteriorRouterPort> rtrPort;
    ApiServer apiStarter;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;
    TapWrapper tap4;

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
        Router rtr =
            apiClient.addRouter().tenantId("pgroup_tnt").name("rtr1").create();
        // Add a interior port to the router.
        rtrPort = rtr
            .addInteriorRouterPort()
            .portAddress(rtrIp.getAddress().toString())
            .networkAddress(rtrIp.getAddress().toString())
            .networkLength(rtrIp.getPrefixLen())
            .create();
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .dstNetworkAddr(rtrPort.getNetworkAddress())
            .dstNetworkLength(rtrPort.getNetworkLength())
            .nextHopPort(rtrPort.getId())
            .type(DtoRoute.Normal).weight(10)
            .create();

        // Build a bridge
        Bridge br =
            apiClient.addBridge().tenantId("pgroup_tnt").name("br").create();
        // Link the bridge to the router.
        BridgePort<DtoInteriorBridgePort> logBrPort =
            br.addInteriorPort().create();
        rtrPort.link(logBrPort.getId());

        // A port group is basically a tag that can be attached to a vport.
        // Although a port group implies a set of vport UUIDs, it's not
        // possible list a group's members (nor is it needed).

        // A security group is a chain of Accept rules that ends with a
        // Drop rule.

        // All sec groups in this test should allow packets from the router's
        // port (address 10.0.0.1) as well as ARP.
        RuleChain commonChain = apiClient.addChain()
            .name("common").tenantId("pgroup_tnt").create();
        log.debug("ChainID for common is {}", commonChain.getId());
        commonChain.addRule().type(DtoRule.Accept).position(1)
                .nwSrcAddress(rtrIp.getAddress().toString())
                .nwSrcLength(32).create();
        commonChain.addRule().type(DtoRule.Accept).position(2)
            .dlType(Unsigned.unsign(ARP.ETHERTYPE)).create();

        // SecGroup 1 allows receiving packets from 10.1.1.0/24.
        // Port Group 1 tracks vports assigned filter SecGroup1
        // Create a port group for SecGroup1's membership.
        PortGroup portG1 = apiClient.addPortGroup()
            .name("PG1").tenantId("pgroup_tnt").create();
        RuleChain secG1 = apiClient.addChain()
            .name("SG1").tenantId("pgroup_tnt").create();
        log.debug("ChainID for SG1 is {}", secG1.getId());
        secG1.addRule().type(DtoRule.Accept).position(1)
            .nwSrcAddress("10.1.1.0").nwSrcLength(24).create();

        // SecGroup2 allows receiving packets from 10.2.2.0/24, and from
        // members of portGroup1 and portGroup2.
        PortGroup portG2 = apiClient.addPortGroup()
            .name("PG2").tenantId("pgroup_tnt").create();
        RuleChain secG2 = apiClient.addChain()
            .name("SG2").tenantId("pgroup_tnt").create();
        log.debug("ChainID for SG2 is {}", secG2.getId());
        secG2.addRule().type(DtoRule.Accept).position(1)
            .portGroup(portG1.getId()).create();
        secG2.addRule().type(DtoRule.Accept).position(2)
            .portGroup(portG2.getId()).create();
        secG2.addRule().type(DtoRule.Accept).position(3)
            .nwSrcAddress("10.2.2.0").nwSrcLength(24).create();

        // Create the bridge's 1st exterior port. It's in Group1.
        RuleChain portOutChain1 = apiClient.addChain()
            .name("port1_out").tenantId("pgroup_tnt").create();
        log.debug("ChainID for port1_out is {}", portOutChain1.getId());
        portOutChain1.addRule().type(DtoRule.Jump).position(1)
            .jumpChainName("common").jumpChainId(commonChain.getId()).create();
        portOutChain1.addRule().type(DtoRule.Jump).position(2)
            .jumpChainName("SG1").jumpChainId(secG1.getId()).create();
        portOutChain1.addRule().type(DtoRule.Drop).position(3).create();
        BridgePort<DtoBridgePort> brPort1 = br.addExteriorPort()
            .outboundFilterId(portOutChain1.getId()).create();
        // XXX: Add the port to portG1 port group.

        // The bridge's 2nd exterior port is in Group2.
        RuleChain portOutChain2 = apiClient.addChain()
            .name("port2_out").tenantId("pgroup_tnt").create();
        log.debug("ChainID for port2_out is {}", portOutChain2.getId());
        portOutChain2.addRule().type(DtoRule.Jump).position(1)
            .jumpChainName("common").jumpChainId(commonChain.getId()).create();
        portOutChain2.addRule().type(DtoRule.Jump).position(2)
            .jumpChainName("SG2").jumpChainId(secG2.getId()).create();
        portOutChain2.addRule().type(DtoRule.Drop).position(3).create();
        BridgePort<DtoBridgePort> brPort2 = br.addExteriorPort()
            .outboundFilterId(portOutChain2.getId()).create();
        // XXX: Add the port to portG2 port group.

        // The bridge's 3rd exterior port is in Group1 and Group2.
        RuleChain portOutChain3 = apiClient.addChain()
            .name("port3_out").tenantId("pgroup_tnt").create();
        log.debug("ChainID for port3_out is {}", portOutChain3.getId());
        portOutChain3.addRule().type(DtoRule.Jump).position(1)
            .jumpChainName("common").jumpChainId(commonChain.getId()).create();
        portOutChain3.addRule().type(DtoRule.Jump).position(2)
            .jumpChainName("SG1").jumpChainId(secG1.getId()).create();
        portOutChain3.addRule().type(DtoRule.Jump).position(3)
            .jumpChainName("SG2").jumpChainId(secG2.getId()).create();
        portOutChain3.addRule().type(DtoRule.Drop).position(4).create();
        BridgePort<DtoBridgePort> brPort3 = br.addExteriorPort()
            .outboundFilterId(portOutChain3.getId()).create();
        // XXX: Add the port to portG1 and portG2 port groups.

        // The bridge's 4th exterior port is not in any security groups.
        BridgePort<DtoBridgePort> brPort4 = br.addExteriorPort().create();

        tap1 = new TapWrapper("sgTap1");
        tap2 = new TapWrapper("sgTap2");
        tap3 = new TapWrapper("sgTap3");
        tap4 = new TapWrapper("sgTap4");

        // Now bind the exterior ports to the taps.
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

        host.addHostInterfacePort()
            .interfaceName(tap1.getName())
            .portId(brPort1.getId()).create();
        host.addHostInterfacePort()
            .interfaceName(tap2.getName())
            .portId(brPort2.getId()).create();
        host.addHostInterfacePort()
            .interfaceName(tap3.getName())
            .portId(brPort3.getId()).create();
        host.addHostInterfacePort()
            .interfaceName(tap4.getName())
            .portId(brPort4.getId()).create();

        log.info("Waiting for LocalPortActive notifications for ports " +
            "{}, {}, {}, and {}",
            new Object[] {brPort1.getId(), brPort2.getId(),
                brPort3.getId(), brPort4.getId()});

        Set<UUID> activatedPorts = new HashSet<UUID>();
        for (int i = 0; i < 4; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received a LocalPortActive message about {}.",
                activeMsg.portID());
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());
        }
        assertThat("The 4 exterior ports should be active.",
            activatedPorts,
            hasItems(brPort1.getId(), brPort2.getId(),
                brPort3.getId(), brPort4.getId()));
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTapWrapper(tap3);
        removeTapWrapper(tap4);
        stopEmbeddedMidolman();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void test() throws MalformedPacketException {
        MAC mac1 = MAC.fromString("02:aa:bb:cc:dd:d1");
        MAC mac2 = MAC.fromString("02:aa:bb:cc:dd:d2");
        MAC mac3 = MAC.fromString("02:aa:bb:cc:dd:d3");
        MAC mac4 = MAC.fromString("02:aa:bb:cc:dd:d4");
        IPv4Addr ip1 = IPv4Addr.fromString("10.0.0.1");
        IPv4Addr ip2 = IPv4Addr.fromString("10.0.0.2");
        IPv4Addr ip3 = IPv4Addr.fromString("10.0.0.3");
        IPv4Addr ip4 = IPv4Addr.fromString("10.0.0.4");

        // Send ARPs from each edge port so that the bridge can learn MACs.
        MAC rtrMac = MAC.fromString(rtrPort.getPortMac());
        arpAndCheckReply(tap1, mac1, ip1, rtrIp.getAddress(), rtrMac);
        arpAndCheckReply(tap2, mac2, ip2, rtrIp.getAddress(), rtrMac);
        arpAndCheckReply(tap3, mac3, ip3, rtrIp.getAddress(), rtrMac);
        arpAndCheckReply(tap4, mac4, ip4, rtrIp.getAddress(), rtrMac);

        // A packet from 10.3.3.4 and port 4 should fail to arrive at port1
        // because SecGroup1 only accepts packets from 10.1.1.0/24.
        icmpFromTapDoesntArriveAtTap(tap4, tap1, mac4, mac1,
            IPv4Addr.fromString("10.3.3.4"), ip1);

        // A packet from 10.2.2.4 and port 4 should fail to arrive at port1
        // because SecGroup1 only accepts packets from 10.1.1.0/24.
        icmpFromTapDoesntArriveAtTap(tap4, tap1, mac4, mac1,
            IPv4Addr.fromString("10.2.2.4"), ip1);

        // A packet from 10.1.1.4 and port 4 should arrive at port1 because its
        // nwSrc is inside an acceptable prefix.
        icmpFromTapArrivesAtTap(tap4, tap1, mac4, mac1,
            IPv4Addr.fromString("10.1.1.4"), ip1);

        // A packet from 10.1.1.4 and port 4 should fail to arrive at port2
        // because port 4 isn't in the SecGroups accepted by SecGroup2 nor is
        // the packet's source IP in 10.2.2.0/24.
        icmpFromTapDoesntArriveAtTap(tap4, tap2, mac4, mac2,
            IPv4Addr.fromString("10.1.1.4"), ip2);

        // A packet from 10.3.3.4 and port 4 should fail to arrive at port2
        // because port 4 isn't in the SecGroups accepted by SecGroup2 nor is
        // the packet's source IP in 10.2.2.0/24.
        icmpFromTapDoesntArriveAtTap(tap4, tap2, mac4, mac2,
            IPv4Addr.fromString("10.3.3.4"), ip2);

        // A packet from 10.2.2.4 and port 4 should arrive at port2 because its
        // nwSrc is inside an acceptable prefix.
        icmpFromTapArrivesAtTap(tap4, tap2, mac4, mac2,
            IPv4Addr.fromString("10.2.2.4"), ip2);

        // A packet from 10.3.3.4 and port 4 should fail to arrive at port3
        // because it's not from an accepted SecGroup or IP src prefix.
        icmpFromTapDoesntArriveAtTap(tap4, tap3, mac4, mac3,
            IPv4Addr.fromString("10.3.3.4"), ip3);

        // A packet from 10.1.1.4 and port 4 should arrive at port3 because its
        // nwSrc is inside an acceptable prefix.
        icmpFromTapArrivesAtTap(tap4, tap3, mac4, mac3,
            IPv4Addr.fromString("10.1.1.4"), ip3);

        // A packet from 10.2.2.4 and port 4 should arrive at port3 because its
        // nwSrc is inside an acceptable prefix.
        icmpFromTapArrivesAtTap(tap4, tap3, mac4, mac3,
            IPv4Addr.fromString("10.2.2.4"), ip3);

        // A packet from 10.3.3.3 and port 3 should fail to arrive at port1
        // even though they are in the same security group. SecGroup1 does not
        // necessarily accept packets from members of its own group.
        icmpFromTapDoesntArriveAtTap(tap3, tap1, mac3, mac1,
            IPv4Addr.fromString("10.3.3.3"), ip1);

        // A packet from 10.3.3.3 and port 3 should arrive at port2 because
        // they're both in SecGroup2, which accepts packets from its own
        // members.
        icmpFromTapArrivesAtTap(tap3, tap2, mac3, mac2,
            IPv4Addr.fromString("10.3.3.3"), ip2);

        // A packet from 10.3.3.1 and port 1 should arrive at port2 because
        // port2 is in SecGroup2 which accepts packets from SecGroup1.
        icmpFromTapArrivesAtTap(tap1, tap2, mac1, mac2,
            IPv4Addr.fromString("10.3.3.1"), ip2);

        // A packet from 10.3.3.2 and port 2 should arrive at port3 because
        // they're both in SecGroup2, which accepts packets from its own
        // members.
        icmpFromTapArrivesAtTap(tap2, tap3, mac2, mac3,
            IPv4Addr.fromString("10.3.3.2"), ip3);

        // A packet from 10.3.3.1 and port 1 should arrive at port3 because
        // port3 is in SecGroup2 which accepts packets from SecGroup1.
        icmpFromTapArrivesAtTap(tap1, tap3, mac1, mac3,
            IPv4Addr.fromString("10.3.3.1"), ip3);

        // A packet from 10.3.3.1 and port 1 should arrive at port4 because
        // port4 isn't in any Security Group.
        icmpFromTapArrivesAtTap(tap1, tap4, mac1, mac4,
            IPv4Addr.fromString("10.3.3.1"), ip4);
    }
}
