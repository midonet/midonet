/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.functional_test;

import java.io.File;

import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.client.MidonetApi;
import org.midonet.client.resource.*;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.tools.timed.Timed.Execution;

import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.midonet.util.Waiters.waitFor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Test Suite that will exercise the interface management subsystem.
 */
public class InterfaceManagementTest {

    private static final Logger log = LoggerFactory
        .getLogger(InterfaceManagementTest.class);

    final String TENANT_NAME = "interface-mgmt-test";
    final String testConfigurationPath =
            "midolman_runtime_configurations/midolman-default.conf";

    private ApiServer apiStarter;
    private MidonetApi apiClient;
    private File config;

    private static int tapInterfaceId = 1;

    private TapWrapper tapWrapper = null;
    private EmbeddedMidolman mm = null;

    private String newTapName() {
        return "tstIMTap" + (++tapInterfaceId);
    }

    @Before
    public void setUp() throws Exception {
        config = new File(testConfigurationPath);

        log.info("Starting embedded zookeper");
        int zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new ApiServer(zkPort);
        apiClient = new MidonetApi(apiStarter.getURI());
    }

    @After
    public void tearDown() {
        if (mm != null)
            mm.stopMidolman();
        if (tapWrapper != null)
            tapWrapper.remove();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    private Execution<Host> hostWaiter(final boolean alive) {
        return new Execution<Host>() {
            @Override
            protected void _runOnce() throws Exception {
                ResourceCollection<Host> hosts = apiClient.getHosts();
                if (hosts.size() == 1 && hosts.get(0).isAlive() == alive) {
                    setResult(hosts.get(0));
                    setCompleted(true);
                }
            }
        };
    }

    private Execution<HostInterface> interfaceWaiter(
            final Matcher<HostInterface> matcher) {

        return new Execution<HostInterface>() {
            @Override
            protected void _runOnce() throws Exception {
                final Host host = waitFor("host update", hostWaiter(true));
                final ResourceCollection<HostInterface> ifaces = host.getInterfaces();
                for (HostInterface iface: ifaces) {
                    if (matcher.matches(iface)) {
                        setResult(iface);
                        setCompleted(true);
                    }
                }
            }
        };
    }

    private Matcher<HostInterface> isThisTap(final String name) {
        return allOf(notNullValue(), hasProperty("name", equalTo(name)));
    }

    private Matcher<HostInterface> hasThisMac(final String name, final String mac) {
        return allOf(notNullValue(),
                     hasProperty("name", equalTo(name)),
                     hasProperty("mac", equalTo(mac)));
    }

    @Test
    public void testHostIsMarkedAsDownWhenTheAgentDies() throws Exception {
        assertThat("No hosts exist", apiClient.getHosts(), hasSize(0));
        mm = startEmbeddedMidolman(config.getAbsolutePath());
        waitFor("A new host appeared", hostWaiter(true));
        mm.stopMidolman();
        waitFor("Host was marked as inactive", hostWaiter(false));
    }

    @Test
    public void testHostIsMarkedAsAliveAfterAgentRestarts() throws Exception {
        assertThat("No hosts exist", apiClient.getHosts(), hasSize(0));
        mm = startEmbeddedMidolman(config.getAbsolutePath());
        waitFor("A new host appeared", hostWaiter(true));
        mm.stopMidolman();
        waitFor("Host was marked as inactive", hostWaiter(false));
        mm.startMidolman(config.getAbsolutePath());
        waitFor("Host was marked as alive after restarting", hostWaiter(true));
    }

    @Test
    public void testNewInterfaceBecomesVisible() throws Exception {
        final String tapName = "newTapInt1";

        mm = startEmbeddedMidolman(config.getAbsolutePath());
        final Host thisHost = waitFor("A new host appeared", hostWaiter(true));
        assertThat("New host apperared", thisHost, notNullValue());

        ResourceCollection<HostInterface> ifaces = thisHost.getInterfaces();
        assertThat("List of interfaces is correct", ifaces,
                allOf(notNullValue(), hasSize(greaterThanOrEqualTo(0))));

        assertThat("The interface we want to create does not already exist",
                ifaces, not(hasItem(isThisTap(tapName))));

        tapWrapper = new TapWrapper(tapName);
        HostInterface newIface = waitFor(
                "the new interface becomes available on the host",
                interfaceWaiter(isThisTap(tapName)));

        assertThat("the new interface appeared properly on the host",
                newIface, notNullValue());
    }

}
