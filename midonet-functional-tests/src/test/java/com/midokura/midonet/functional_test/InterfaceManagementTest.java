/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.not;

import com.midokura.midolman.mgmt.data.dto.client.DtoHost;
import com.midokura.midolman.mgmt.data.dto.client.DtoInterface;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.tools.timed.Timed;

/**
 * Test Suite that will exercise the interface management subsystem.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/27/12
 */
public class InterfaceManagementTest extends AbstractSmokeTest {

    MidolmanMgmt api;

    @Before
    public void setUp() throws Exception {
        cleanupZooKeeperData();
        api = new MockMidolmanMgmt(false);
    }

    @After
    public void tearDown() throws Exception {
        stopMidolmanMgmt(api);
    }

    @Test
    @Ignore
    public void testNewHostAppearsWhenTheAgentIsExecuted() throws Exception {

        DtoHost[] hosts = api.getHosts();
        assertThat("No hosts should be visible now",
                   hosts, allOf(notNullValue(), arrayWithSize(0)));

        // create a new one
        // start the agent / or midolman1
        MidolmanLauncher launcher =
            MidolmanLauncher.start(MidolmanLauncher.CONFIG_WITH_NODE_AGENT);

        try {
            hosts =
                waitFor("a new host should come up", 10 * 1000, 250,
                        new Timed.Execution<DtoHost[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHosts());

                                // check for early finish
                                setCompleted(getResult().length == 1);
                            }
                        });

            assertThat("there is one more host now",
                       hosts, allOf(notNullValue(), arrayWithSize(1)));

            assertThat("the new host is marked as active",
                       hosts[0].isAlive(), equalTo(true));
        } finally {
            launcher.stop();
        }
    }

    @Test
    @Ignore
    public void testHostIsMarkedAsDownWhenTheAgentDies() throws Exception {
        DtoHost[] hosts = api.getHosts();
        assertThat("No hosts should be visible now",
                   hosts, allOf(notNullValue(), arrayWithSize(0)));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(MidolmanLauncher.CONFIG_WITH_NODE_AGENT);

        try {
            DtoHost[] newHosts =
                waitFor("a new host appeared",
                        new Timed.Execution<DtoHost[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHosts());
                                setCompleted(getResult().length == 1);
                            }
                        });

            assertThat("A new host is listed in the list of hosts",
                       newHosts, allOf(notNullValue(), arrayWithSize(1)));

            final DtoHost hostInfo = newHosts[0];

            assertThat("The host is marked as alive",
                       hostInfo.isAlive(), equalTo(true));

            launcher.stop();

            DtoHost newHostInfo =
                waitFor("host status change",
                        new Timed.Execution<DtoHost>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHost(hostInfo.getUri()));
                                setCompleted(
                                    getResult().isAlive() != hostInfo.isAlive());
                            }
                        });

            assertThat("The host was marked as down after midolman exited",
                       newHostInfo.isAlive(),
                       Matchers.equalTo(false));

        } finally {
            launcher.stop();
        }
    }

    @Test
    @Ignore
    public void testHostIsMarkedAsAliveAfterAgentRestarts() throws Exception {
        DtoHost[] hosts = api.getHosts();
        assertThat("No hosts should be visible now",
                   hosts, allOf(notNullValue(), arrayWithSize(0)));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(MidolmanLauncher.CONFIG_WITH_NODE_AGENT);

        try {
            hosts = waitFor("a new host should appear",
                            new Timed.Execution<DtoHost[]>() {
                                @Override
                                protected void _runOnce() throws Exception {
                                    setResult(api.getHosts());
                                    setCompleted(getResult().length == 1);
                                }
                            });

            assertThat("A new host is listed in the list of hosts",
                       hosts, allOf(notNullValue(), arrayWithSize(1)));

            final DtoHost hostInfo = hosts[0];

            assertThat("The host is marked as alive",
                       hostInfo.isAlive(), equalTo(true));

            launcher.stop();

            DtoHost newHostInfo =
                waitFor("host status change",
                        new Timed.Execution<DtoHost>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHost(hostInfo.getUri()));
                                setCompleted(!getResult().isAlive());
                            }
                        });

            assertThat("The host was marked as down after midolman exited",
                       newHostInfo.isAlive(), equalTo(false));

            // start the agent again
            launcher = MidolmanLauncher.start();

            newHostInfo =
                waitFor("host status change",
                        new Timed.Execution<DtoHost>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHost(hostInfo.getUri()));
                                setCompleted(getResult().isAlive());
                            }
                        });

            assertThat("The host was marked as alive after midolman restarts",
                       newHostInfo.isAlive(), equalTo(true));

        } finally {
            launcher.stop();
        }
    }

    @Test
    @Ignore
    public void testNewInterfaceBecomesVisible() throws Exception {

        final String tapInterfaceName = "newTapInterface";

        MidolmanLauncher launcher =
            MidolmanLauncher.start(MidolmanLauncher.CONFIG_WITH_NODE_AGENT);

        TapWrapper tapWrapper = null;

        try {
            DtoHost[] hosts =
                waitFor("a new host should appear",
                        new Timed.Execution<DtoHost[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHosts());
                                setCompleted(getResult().length == 1);
                            }
                        });
            assertThat("A new host is listed in the list of hosts",
                       hosts, allOf(notNullValue(), arrayWithSize(1)));

            final DtoHost host = hosts[0];
            assertThat("The new hosts is marked as alive!",
                       host.isAlive(), equalTo(true));

            DtoInterface[] interfaces = api.getHostInterfaces(host);
            assertThat("The interfaces array is properly formatted",
                       interfaces,
                       allOf(
                           notNullValue(),
                           arrayWithSize(greaterThanOrEqualTo(0))));

            final Matcher<DtoInterface[]> hasNewTapMatcher =
                hasItemInArray(hasProperty("name", equalTo(tapInterfaceName)));

            assertThat(
                "The interface we want to create does not already exists",
                interfaces,
                not(hasNewTapMatcher));

            tapWrapper = new TapWrapper(tapInterfaceName);

            interfaces =
                waitFor("the new interface to become visible on the host",
                        new Timed.Execution<DtoInterface[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHostInterfaces(host));
                                setCompleted(
                                    hasNewTapMatcher.matches(getResult()));
                            }
                        });

            assertThat("the new interface appeared properly on the host",
                       interfaces, hasNewTapMatcher);
        } finally {
            launcher.stop();
            removeTapWrapper(tapWrapper);
        }
    }
}
