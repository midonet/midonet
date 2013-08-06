/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import akka.actor.ActorRef;
import akka.testkit.TestProbe;
import akka.util.Duration;
import com.google.common.base.Strings;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.midonet.midolman.routingprotocols.RoutingHandler;
import org.midonet.midolman.routingprotocols.RoutingManagerActor;
import org.midonet.client.MidonetApi;
import org.midonet.client.resource.Bgp;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.util.process.ProcessHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

public class BgpCleanUpTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    @Rule
    public TestWatcher testWatcher = new TestWatcher() {
        private String separator = Strings.repeat("-", 72);

        @Override
        protected void starting(Description description) {
            print("starting", description);
        }

        @Override
        protected void finished(Description description) {
            print("finished", description);
        }

        @Override
        protected void succeeded(Description description) {
            print("succeeded", description);
        }

        @Override
        public void failed(Throwable e, Description description) {
            print("failed", description);
        }

        private void print(String event, Description description) {
            log.info(separator);
            log.info("{}: {}", event, description);
            log.info(separator);
        }
    };

    ApiServer apiStarter;
    MidonetApi apiClient;
    EmbeddedMidolman mm;
    TestProbe probe;

    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";

    static final String testConfigurationPath =
        "midolman_runtime_configurations/midolman-with_bgp.conf";
    static final String bgpdBinaryPath = "/usr/lib/quagga/bgpd";
    static final String bgpdConfigPath = "/etc/quagga/bgpd.conf";


    @Before
    public void setUp() {
        File testConfigurationFile = new File(testConfigurationPath);
        assertThat("configuration file exists", testConfigurationFile.exists());

        File bgpdBinaryFile = new File(bgpdBinaryPath);
        assertThat("bgpd binary exists", bgpdBinaryFile.exists());

        File bgpdConfigFile = new File(bgpdConfigPath);
        assertThat("bgpd config exists", bgpdConfigFile.exists());

        // start zookeeper with the designated port.
        log.info("Starting embedded zookeeper.");
        int zookeeperPort = startEmbeddedZookeeper(testConfigurationPath);
        assertThat(zookeeperPort, greaterThan(0));

        log.info("Starting cassandra");
        startCassandra();

        log.info("Starting REST API");
        apiStarter = new ApiServer(zookeeperPort);
        apiClient = new MidonetApi(apiStarter.getURI());

        log.info("Starting midolman");
        mm = startEmbeddedMidolman(
                testConfigurationFile.getAbsolutePath());
        probe = new TestProbe(mm.getActorSystem());

        assertThat(mm.getActorSystem().eventStream().subscribe(
                probe.ref(), RoutingHandler.BGPD_STATUS.class), equalTo(true));
    }

    @After
    public void tearDown() {
        stopEmbeddedMidolman();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    @Test
    public void startBgpWithDirtyEnv() {
        /*
         * set up minimum BGP scenario, no peer needed.
         */
        String tenantName = "tenant";
        Router router = apiClient.addRouter().tenantId(tenantName).name("router").create();
        log.debug("Created router " + router.getName());

        RouterPort materializedRouterPort_bgp = (RouterPort) router.addPort()
                .portAddress("100.0.0.1")
                .networkAddress("100.0.0.0")
                .networkLength(30)
                .portMac("02:00:00:00:aa:01")
                .create();
        log.debug("Created materialized router port - BGP: " + materializedRouterPort_bgp.toString());

        Bgp bgp = materializedRouterPort_bgp.addBgp()
                .localAS(1)
                .peerAddr("100.0.0.2")
                .peerAS(2)
                .create();
        log.debug("Created BGP {} in materialized router port {} ",
                bgp.toString(), materializedRouterPort_bgp.toString());

        String tapName = "bgpTap";
        TapWrapper bgpTap = new TapWrapper(tapName);

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

        // Add the namespace and the interfaces BGP will use, so there's a
        // conflict
        String cmdLine = "sudo ip netns add mbgp1_ns";
        ProcessHelper.executeCommandLine(cmdLine);

        cmdLine = "sudo ip link add name mbgp1 type veth peer name mbgp1_m";
        ProcessHelper.executeCommandLine(cmdLine);

        cmdLine = "sudo ip link set mbgp1_m netns mbgp1_ns";
        ProcessHelper.executeCommandLine(cmdLine);

        cmdLine = "sudo ip netns exec mbgp1_ns sudo ifconfig mbgp1_m up";
        ProcessHelper.executeCommandLine(cmdLine);

        cmdLine = "sudo ip netns exec mbgp1_ns sleep 30";
        ProcessHelper.newDemonProcess(cmdLine);

        cmdLine = "sudo ip netns exec mbgp1_ns " +
                bgpdBinaryPath +
                " --vty_port 2606" +
                " --config_file " + bgpdConfigPath +
                " --pid_file /var/run/quagga/bgpd.2606.pid ";
        ProcessHelper.newDemonProcess(cmdLine);

        // Bind the interface -> starts BGP
        host.addHostInterfacePort()
                .interfaceName(tapName)
                .portId(materializedRouterPort_bgp.getId())
                .create();


        // if bgpd gets active, that means everything went ok
        assertThat(isBgpActive(materializedRouterPort_bgp.getId()), equalTo(true));

        // clean up
        cmdLine = "sudo ip link del " + tapName;
        ProcessHelper.executeCommandLine(cmdLine);
    }

    @Test
    public void midolmanStopClearsBgp() {
        /*
         * set up minimum BGP scenario, no peer needed.
         */
        String tenantName = "tenant";
        Router router = apiClient.addRouter().tenantId(tenantName).name("router").create();
        log.debug("Created router " + router.getName());

        RouterPort materializedRouterPort_bgp = (RouterPort) router.addPort()
                .portAddress("100.0.0.1")
                .networkAddress("100.0.0.0")
                .networkLength(30)
                .portMac("02:00:00:00:aa:01")
                .create();
        log.debug("Created materialized router port - BGP: " + materializedRouterPort_bgp.toString());

        Bgp bgp = materializedRouterPort_bgp.addBgp()
                .localAS(1)
                .peerAddr("100.0.0.2")
                .peerAS(2)
                .create();
        log.debug("Created BGP {} in materialized router port {} ",
                bgp.toString(), materializedRouterPort_bgp.toString());

        String tapName = "bgpTap";
        TapWrapper bgpTap = new TapWrapper(tapName);

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

        // Bind the interface -> starts BGP
        host.addHostInterfacePort()
                .interfaceName(tapName)
                .portId(materializedRouterPort_bgp.getId())
                .create();


        // wait for bgpd to be active
        assertThat(isBgpActive(materializedRouterPort_bgp.getId()), equalTo(true));

        // stop RoutingManagerActor
        log.debug("stopping RoutingManagerActor");
        ActorRef rma = RoutingManagerActor.getRef(mm.getActorSystem());
        mm.getActorSystem().stop(rma);

        // check that stopping midolman clears bgp stuff
        assertThat(isBgpActive(materializedRouterPort_bgp.getId()), equalTo(false));

        // clean up
        String cmdLine = "sudo ip link del " + tapName;
        ProcessHelper.executeCommandLine(cmdLine);

    }

    private Boolean isBgpActive(UUID portId) {
        log.debug("waiting for BGPD_STATUS");
        RoutingHandler.BGPD_STATUS bgpdStatus = probe.expectMsgClass(
                Duration.create(20, TimeUnit.SECONDS), RoutingHandler.BGPD_STATUS.class);
        log.debug("BGPD_STATUS received");

        assertThat(bgpdStatus, notNullValue());
        assertThat(bgpdStatus.port(), equalTo(portId));

        return bgpdStatus.isActive();
    }

}
