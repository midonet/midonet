package com.midokura.midonet.functional_test;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.Waiters;
import com.midokura.util.process.ProcessHelper;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.client.MidonetApi;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class MgmtZookeeperReconnection {
    private final static Logger log =
        LoggerFactory.getLogger(MgmtZookeeperReconnection.class);

    final String TENANT_NAME = "tenant-zk-disconnection-test";
    final String testConfigurationPath =
            "midolman_runtime_configurations/midolman-default.conf";

    Bridge bridge;

    ApiServer apiStarter;
    MidonetApi apiClient;

    int zkPort;

    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";

    @Before
    public void setUp() throws Exception {
        log.info("Starting embedded zookeper");
        zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new ApiServer(zkPort);
        apiClient = new MidonetApi(apiStarter.getURI());
    }

    @After
    public void tearDown() throws Exception {
        unblockCommunications();
        stopEmbeddedMidolman();
        apiStarter.stop();
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
    public void testNoDisconnection() throws Exception {
        log.info("Trying to create a bridge");
        bridge = apiClient.addBridge().
                    tenantId(TENANT_NAME).
                    name("bridgeSuccess").
                    create();
    }

    @Test
    public void testDisconnection() throws Exception {
        log.info("blocking communications with zookeeper");
        assertThat("iptables command is successful", blockCommunications() == 0);

        log.info("Trying to create a bridge");
        try {
            bridge = apiClient.addBridge().
                        tenantId(TENANT_NAME).
                        name("bridgeFailure").
                        create();
        } catch (Exception e) {
            log.info("API request failed, as expected: ", e.toString());
        }
        Waiters.sleepBecause("We want the ZK session to expire", 16);

        log.info("turning communications with zookeeper back on");
        assertThat("iptables command is successful", unblockCommunications() == 0);

        Waiters.sleepBecause("We want a new ZK session to be established", 5);

        log.info("We can now create a new bridge");
        bridge = apiClient.addBridge().
                    tenantId(TENANT_NAME).
                    name("bridgeSuccess").
                    create();
    }
}
