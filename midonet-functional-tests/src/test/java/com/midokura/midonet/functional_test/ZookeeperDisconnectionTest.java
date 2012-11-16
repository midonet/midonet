package com.midokura.midonet.functional_test;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import akka.actor.ActorRef;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.pattern.Patterns;
import akka.util.Duration;
import com.midokura.util.Waiters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midolman.topology.VirtualTopologyActor;
import com.midokura.midolman.topology.VirtualTopologyActor.BridgeRequest;
import com.midokura.util.process.ProcessHelper;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static org.junit.Assert.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ZookeeperDisconnectionTest {
    private final static Logger log = LoggerFactory.getLogger(BaseTunnelTest.class);

    final String TENANT_NAME = "tenant-tunnel-test";
    final String testConfigurationPath =
            "midolmanj_runtime_configurations/midolman-default.conf";

    BridgePort localPort, remotePort;
    Bridge bridge;
    Host thisHost;
    UUID thisHostId;

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
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
        apiStarter = new MockMgmtStarter(zkPort);
        apiClient = new MidonetMgmt(apiStarter.getURI());
        log.info("Starting midolman");
        midolman = startEmbeddedMidolman(testConfigFile.getAbsolutePath());
        dataClient = midolman.getDataClient();

        // Create a bridge with two ports
        log.info("Creating bridge and two ports.");
        bridge = apiClient.addBridge().tenantId(TENANT_NAME).name("br1").create();
        localPort = bridge.addMaterializedPort().create();
        remotePort = bridge.addMaterializedPort().create();

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
    }

    @After
    public void tearDown() throws Exception {
        unblockCommunications();
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    private String makeCommand(String op) {
        return String.format("sudo iptables -%s INPUT -p tcp --dport %d -j DROP", op, zkPort);
    }

    private int blockCommunications() {
        return ProcessHelper.newProcess(makeCommand("I")).
                    logOutput(log, "blockCommunications").
                    runAndWait();
    }

    private int unblockCommunications() {
        return ProcessHelper.newProcess(makeCommand("D")).
                    logOutput(log, "unblockCommunications").
                    runAndWait();
    }

    @Test
    public void testDisconnection() throws Exception {
        log.info("blocking communications with zookeeper");
        assertThat("iptables command is successful", blockCommunications() == 0);

        log.info("sending a bridge request to the VirtualTopologyActor");
        ActorRef vta = VirtualTopologyActor.getRef(midolman.getActorSystem());
        Future<Object> bridgeFuture =
            Patterns.ask(vta, new BridgeRequest(bridge.getId(), true), 30000);
        Waiters.sleepBecause("We want the ZK request to fail", 16);

        assertThat("Bridge future has not completed", !bridgeFuture.isCompleted());

        log.info("turning communications with zookeeper back on");
        assertThat("iptables command is successful", unblockCommunications() == 0);

        log.info("waiting for the RCU bridge to be sent to us");
        Object result = Await.result(bridgeFuture, Duration.parse("30 seconds"));
        assertThat("result is of Bridge type", result,
                   instanceOf(com.midokura.midolman.simulation.Bridge.class));

    }
}
