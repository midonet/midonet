/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static java.lang.String.format;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.midokura.midolman.mgmt.data.dto.client.DtoHost;
import com.midokura.midolman.mgmt.data.dto.client.DtoInterface;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.util.Sudo;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.tools.timed.Timed;
import com.midokura.util.process.ProcessHelper;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.With_Node_Agent;

/**
 * Test Suite that will exercise the interface management subsystem.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/27/12
 */
public class InterfaceManagementTest extends FunctionalTestsHelper {

    MidolmanMgmt api;

    @Before
    public void setUp() throws Exception {
        cleanupZooKeeperData();
        api = new MockMidolmanMgmt(false);
    }

    @After
    public void tearDown() throws Exception {
        stopMidolmanMgmt(api);
	cleanupZooKeeperData();
    }

    @Test
    public void testNewHostAppearsWhenTheAgentIsExecuted() throws Exception {

        DtoHost[] hosts = api.getHosts();
        assertThat("We didn't start with no hosts registered",
                   hosts, allOf(notNullValue(), arrayWithSize(0)));

        // create a new one
        // start the agent / or midolman1
        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testNewHostAppearsWhenTheAgentIsExecuted");

        try {
            hosts =
                waitFor("a new host should come up", 10 * 1000, 500,
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
    public void testHostIsMarkedAsDownWhenTheAgentDies() throws Exception {
        DtoHost[] hosts = api.getHosts();
        assertThat("No hosts should be visible now",
                   hosts, allOf(notNullValue(), arrayWithSize(0)));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testHostIsMarkedAsDownWhenTheAgentDies");

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
    public void testHostIsMarkedAsAliveAfterAgentRestarts() throws Exception {
        DtoHost[] hosts = api.getHosts();
        assertThat("No hosts should be visible now",
                   hosts, allOf(notNullValue(), arrayWithSize(0)));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testHostIsMarkedAsAliveAfterAgentRestarts");

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
            launcher = MidolmanLauncher.start(With_Node_Agent,
                                              "InterfaceManagementTest.testHostIsMarkedAsAliveAfterAgentRestarts-restarted");

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
    public void testNewInterfaceBecomesVisible() throws Exception {

        final String tapInterfaceName = "newTapInterface";

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testNewInterfaceBecomesVisible");

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

    @Test
    public void testCreateInterfaceOnHost() throws Exception {
        final String tapInterfaceName = "newTapInt2";

        assertThat("We were expecting no hosts to be registered",
                   api.getHosts(), arrayWithSize(0));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testCreateInterfaceOnHost");

        TapWrapper tapWrapper = null;

        try {
            DtoHost[] hosts =
                waitFor("a host should register",
                        new Timed.Execution<DtoHost[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHosts());
                                setCompleted(getResult().length == 1);
                            }
                        });

            final DtoHost host = hosts[0];
            assertThat("The new host should be alive!", host.isAlive());

            DtoInterface[] interfaces = api.getHostInterfaces(host);
            assertThat("We should have seen some interfaces",
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

            DtoInterface dtoInterface = new DtoInterface();
            dtoInterface.setName(tapInterfaceName);
            dtoInterface.setType(DtoInterface.Type.Virtual);
            api.addInterface(host, dtoInterface);

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

            tapWrapper = new TapWrapper(tapInterfaceName, false);
        } finally {
            launcher.stop();
            removeTapWrapper(tapWrapper);
        }
    }

    @Test
    public void testUpdateInterfaceMacForHost() throws Exception {
        final String tapInterfaceName = "newTapInt3";

        assertThat("We were expecting no hosts to be registered",
                   api.getHosts(), arrayWithSize(0));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testUpdateInterfaceMacForHost");

        TapWrapper tapWrapper = new TapWrapper(tapInterfaceName);

        try {
            final DtoHost host = waitForHostRegistration();

            final Matcher<DtoInterface[]> tapMatcher =
                hasItemInArray(hasProperty("name", equalTo(tapInterfaceName)));

            DtoInterface[] interfaces =
                waitFor("the interface to be exposed via API",
                        new Timed.Execution<DtoInterface[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHostInterfaces(host));
                                setCompleted(tapMatcher.matches(getResult()));
                            }
                        });

            DtoInterface dtoInterface = null;
            for (DtoInterface anInterface : interfaces) {
                if (anInterface.getName().equals(tapInterfaceName)) {
                    dtoInterface = anInterface;
                    break;
                }
            }

            String targetMacAddress = "12:11:11:11:11:17";

            assertThat(
                "the interface dto is ok (non null and with a different mac)",
                dtoInterface,
                allOf(notNullValue(),
                      hasProperty("mac", not(equalTo(targetMacAddress)))));

            //noinspection ConstantConditions
            dtoInterface.setMac(targetMacAddress);

            final Matcher<DtoInterface[]> newMacMatcher =
                hasItemInArray(
                    allOf(
                        hasProperty("mac", equalTo(targetMacAddress)),
                        hasProperty("name", equalTo(dtoInterface.getName()))
                    )
                );

            // make the update
            api.updateInterface(dtoInterface);

            // wait to see the change
            waitFor("the interface MAC address to change",
                    new Timed.Execution<java.lang.Object>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            setResult(api.getHostInterfaces(host));
                            setCompleted(newMacMatcher.matches(getResult()));
                        }
                    });
        } finally {
            launcher.stop();
            removeTapWrapper(tapWrapper);
        }
    }

    @Test
    public void testUpdateInterfaceAddressForHost() throws Exception {
        final String tapInterfaceName = "newTapInt4";

        assertThat("We were expecting no hosts to be registered",
                   api.getHosts(), arrayWithSize(0));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testUpdateInterfaceAddressForHost");

        TapWrapper tapWrapper = new TapWrapper(tapInterfaceName);

        try {
            final DtoHost host = waitForHostRegistration();

            final Matcher<DtoInterface[]> tapMatcher =
                hasItemInArray(hasProperty("name", equalTo(tapInterfaceName)));

            DtoInterface[] interfaces =
                waitFor("the interface to be exposed via API",
                        new Timed.Execution<DtoInterface[]>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHostInterfaces(host));
                                setCompleted(tapMatcher.matches(getResult()));
                            }
                        });

            DtoInterface dtoInterface = null;
            for (DtoInterface anInterface : interfaces) {
                if (anInterface.getName().equals(tapInterfaceName)) {
                    dtoInterface = anInterface;
                    break;
                }
            }

            String targetIpAddress = "10.56.34.1";
            @SuppressWarnings("ConstantConditions")
            InetAddress addresses[] =
                new InetAddress[dtoInterface.getAddresses().length + 1];

            System.arraycopy(dtoInterface.getAddresses(), 0,
                             addresses, 0, dtoInterface.getAddresses().length);


            addresses[addresses.length - 1] =
                InetAddress.getByName(targetIpAddress);

            assertThat(
                "the interface dto is ok (non null and with a different mac)",
                dtoInterface,
                allOf(notNullValue(),
                      hasProperty("addresses",
                                  not(hasItemInArray(
                                      hasProperty("address", equalTo(
                                          new byte[]{(byte) 10, (byte) 56, (byte) 34, (byte) 1}))
                                  )))));

            //noinspection ConstantConditions
            dtoInterface.setAddresses(addresses);

            final Matcher<DtoInterface[]> addressMatcher =
                hasItemInArray(
                    allOf(
                        hasProperty("name", equalTo(dtoInterface.getName())),
                        hasProperty("addresses", hasItemInArray(
                            hasProperty("address", Matchers.equalTo(
                                new byte[]{(byte) 10, (byte) 56, (byte) 34, (byte) 1}))
                        ))
                    ));

            // make the update
            api.updateInterface(dtoInterface);

            // wait to see the change
            waitFor("the interface MAC address to change",
                    new Timed.Execution<java.lang.Object>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            setResult(api.getHostInterfaces(host));
                            log.debug("{}", getResult());
                            setCompleted(addressMatcher.matches(getResult()));
                        }
                    });
        } finally {
            launcher.stop();
            removeTapWrapper(tapWrapper);
        }
    }

    @Test
    public void testUpdateInterfaceDeleteAddressForHost()
        throws Exception {
        final String tapName = newTapName();

        assertThat("We were expecting no hosts to be registered",
                   api.getHosts(), arrayWithSize(0));

        MidolmanLauncher launcher =
            MidolmanLauncher.start(With_Node_Agent,
                                   "InterfaceManagementTest.testUpdateInterfaceDeleteAddressForHost");

        TapWrapper tapWrapper = new TapWrapper(tapName);

        Sudo.sudoExec(
            format("ip addr add 10.43.56.34/12 dev %s", tapName));

        try {
            final DtoHost host = waitForHostRegistration();

            DtoInterface dtoInterface = waitForNamedInterface(host, tapName);

            assertThat(
                "the interface should be visible with the correct address",
                dtoInterface,
                allOf(notNullValue(),
                      hasProperty("addresses",
                                  hasItemInArray(
                                      hasProperty("hostAddress",
                                                  equalTo("10.43.56.34"))))));

            List<InetAddress> newAddresses = new ArrayList<InetAddress>();
            //noinspection ConstantConditions
            for (InetAddress inetAddress : dtoInterface.getAddresses()) {
                if (!inetAddress.getHostAddress().equals("10.43.56.34")) {
                    newAddresses.add(inetAddress);
                }
            }

            dtoInterface.setAddresses(
                newAddresses.toArray(new InetAddress[newAddresses.size()]));

            final Matcher<DtoInterface[]> addressMatcher =
                hasItemInArray(
                    allOf(
                        hasProperty("name", equalTo(dtoInterface.getName())),
                        hasProperty("addresses", hasItemInArray(
                            hasProperty("hostAddress",
                                        equalTo("10.43.56.34"))))));

            // make the update
            api.updateInterface(dtoInterface);

            // wait to see the change
            waitFor("the interface MAC address to change",
                    new Timed.Execution<java.lang.Object>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            setResult(api.getHostInterfaces(host));
                            setCompleted(addressMatcher.matches(getResult()));
                        }
                    });
        } finally {
            launcher.stop();
            removeTapWrapper(tapWrapper);
        }
    }

    @Test
    public void testBindInterfaceToMidonetPort()
        throws Exception {
        final String tapName = newTapName();

        assertThat("We were expecting no hosts to be registered",
                   api.getHosts(), arrayWithSize(0));

        final String PORT_ID_KEY =
            DtoInterface.PropertyKeys.midonet_port_id.name();

        TapWrapper tap = null;
//      Use this for remote developing (mac IDE + linux)
//        RemoteTap tap = null;
        OvsBridge ovsBridge = null;
        MidolmanLauncher launcher = null;
        try {
//          Use this for remote developing (mac IDE + linux)
//          tap = new RemoteTap(tapName, true);
            tap = new TapWrapper(tapName, true);

            ProcessHelper
                .newProcess(
                    format("ip addr add 10.43.56.34/12 dev %s", tapName))
                .withSudo()
                .runAndWait();

            launcher = MidolmanLauncher.start(With_Node_Agent,
                                              "InterfaceManagementTest.testBindInterfaceToMidonetPort");

            OpenvSwitchDatabaseConnectionImpl ovsdb =
                new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);
            if (ovsdb.hasBridge("smoke-br"))
                ovsdb.delBridge("smoke-br");

            ovsBridge = new OvsBridge(ovsdb, "smoke-br");

            final DtoHost dtoHost = waitForHostRegistration();

            final DtoInterface dtoInterface = waitForNamedInterface(dtoHost,
                                                                    tapName);
            assertThat(
                "the new interface is not yet associated with a midonet port",
                dtoInterface.getProperties(), not(hasKey(PORT_ID_KEY)));

            UUID targetPortId = UUID.randomUUID();
            dtoInterface.getProperties().put(PORT_ID_KEY,
                                             targetPortId.toString());

            api.updateInterface(dtoInterface);

            DtoInterface updatedDtoInterface =
                waitFor("the interface properties should be updated",
                        new Timed.Execution<DtoInterface>() {
                            @Override
                            protected void _runOnce() throws Exception {
                                setResult(api.getHostInterface(dtoInterface));
                                log.debug("Interface: " + getResult());
                                Map<String, String> properties =
                                    getResult().getProperties();

                                setCompleted(
                                    properties.containsKey(PORT_ID_KEY));
                            }
                        });

            assertThat("the interface object is showing the proper port id",
                       updatedDtoInterface.getProperties(),
                       hasEntry(is(PORT_ID_KEY), is(targetPortId.toString())));
        } finally {
            stopMidolman(launcher);
//            removeRemoteTap(tap);
            removeTapWrapper(tap);
            removeBridge(ovsBridge);
        }
    }

    private DtoInterface waitForNamedInterface(final DtoHost host,
                                               String tapInterfaceName)
        throws Exception {
        final Matcher<DtoInterface[]> tapMatcher =
            hasItemInArray(hasProperty("name", equalTo(tapInterfaceName)));

        DtoInterface[] interfaces =
            waitFor("the interface to be exposed via API",
                    new Timed.Execution<DtoInterface[]>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            setResult(api.getHostInterfaces(host));
                            setCompleted(tapMatcher.matches(getResult()));
                        }
                    });

        for (DtoInterface anInterface : interfaces) {
            if (anInterface.getName().equals(tapInterfaceName)) {
                return anInterface;
            }
        }

        return null;
    }

    private DtoHost waitForHostRegistration() throws Exception {
        DtoHost[] hosts =
            waitFor("host registration",
                    new Timed.Execution<DtoHost[]>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            setResult(api.getHosts());
                            setCompleted(getResult().length == 1);
                        }
                    });

        final DtoHost host = hosts[0];
        assertThat("The new host should be alive!", host.isAlive());
        return host;
    }

    private static int tapInterfaceId = 1;

    private String newTapName() {
        return "tstIMTap" + (++tapInterfaceId);
    }
}
