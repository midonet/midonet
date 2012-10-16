/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.File;
import java.io.IOException;

import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.resource.Bgp;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Router;
import com.midokura.midonet.client.resource.RouterPort;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.TapProxy;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
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
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;

public class BgpTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    final String tenantName = "tenant";

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    MidolmanLauncher midolman;

    TapWrapper tap1_vm;

    PacketHelper packetHelper1;

    Bgp bgp1;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";


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
        startEmbeddedMidolman(testConfigurationFile.getAbsolutePath());
        sleepBecause("we need midolman to boot up", 5);

        Router router1 = apiClient.addRouter().tenantId(tenantName).name("router1").create();
        log.debug("Created router " + router1.getName());

        RouterPort materializedRouterPort1_vm = (RouterPort) router1.addMaterializedRouterPort()
                .portAddress("1.0.0.1")
                .networkAddress("1.0.0.0")
                .networkLength(24)
                .portMac("00:00:00:00:01:01")
                .create();
        log.debug("Created logical router port: " + materializedRouterPort1_vm.toString());

        RouterPort materializedRouterPort1_bgp = (RouterPort) router1.addMaterializedRouterPort()
                .portAddress("100.0.0.1")
                .networkAddress("100.0.0.0")
                .networkLength(30)
                .portMac("00:00:00:00:aa:01")
                .create();
        log.debug("Created materialized router port: " + materializedRouterPort1_bgp.toString());

        bgp1 = materializedRouterPort1_bgp.addBgp()
                .localAS(1)
                .peerAddr("100.0.0.2")
                .peerAS(2)
                .create();
        log.debug("Created BGP in materialized router port: " + bgp1.toString());

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
                .portId(materializedRouterPort1_vm.getId())
                .create();

        log.debug("Adding interface to host.");
        host.addHostInterfacePort()
                .interfaceName("veth0")
                .portId(materializedRouterPort1_bgp.getId())
                .create();

        packetHelper1 = new PacketHelper(
                MAC.fromString("00:00:00:00:01:02"),
                IntIPv4.fromString("1.0.0.2"),
                MAC.fromString("00:00:00:00:01:01"),
                IntIPv4.fromString("1.0.0.1"));


        sleepBecause("we need midolman to boot up", 20);

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

        sleepBecause("wait few seconds to see if bgpd catches the route", 20);

        tap1_vm.send(packetHelper1.makeIcmpEchoRequest(IntIPv4.fromString("2.0.0.2")));

        sleepBecause("wait for ICMP to travel", 60);

        log.debug("testRouteConnectivity - stop");
    }
}
