/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.File;

import akka.testkit.TestProbe;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.MidonetApi;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static org.junit.Assert.assertNotNull;


public abstract class TestBase {

    protected final static Logger log = LoggerFactory.getLogger(TestBase.class);
    protected static final String TEST_HOST_ID =
        "910de343-c39b-4933-86c7-540225fb02f9";

    LockHelper.Lock lock;
    final String testConfigurationPath =
            "midolman_runtime_configurations/midolman-default.conf";

    private ApiServer apiStarter;
    protected MidonetApi apiClient;
    private EmbeddedMidolman midolman;
    protected TestProbe probe;
    protected Host thisHost;

    @Before
    public final void setUp() throws Exception {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);

        File testConfigFile = new File(testConfigurationPath);
        log.info("Starting embedded zookeper");
        int zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new ApiServer(zkPort);
        apiClient = new MidonetApi(apiStarter.getURI());
        log.info("Starting midolman");
        midolman = startEmbeddedMidolman(testConfigFile.getAbsolutePath());

        probe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);

        ResourceCollection<Host> hosts = apiClient.getHosts();
        thisHost = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                thisHost = h;
            }
        }
        // check that we've actually found the test host.
        assertNotNull(thisHost);

        setup();
    }

    @After
    public final void tearDown() throws Exception {
        try {
            teardown();
            stopEmbeddedMidolman();
            apiStarter.stop();
            stopCassandra();
            stopEmbeddedZookeeper();
        } finally {
            lock.release();
        }
    }

    protected abstract void setup();
    protected abstract void teardown();
}
