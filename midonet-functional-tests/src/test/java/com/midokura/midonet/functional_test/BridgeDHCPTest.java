/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.midokura.util.Waiters.sleepBecause;
import static com.midokura.util.Waiters.waitFor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Subnet;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.vm.HypervisorType;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.midonet.functional_test.vm.libvirt.LibvirtHandler;
import com.midokura.tools.timed.Timed;
import com.midokura.util.lock.LockHelper;
import com.midokura.util.ssh.SshHelper;
import static com.midokura.hamcrest.RegexMatcher.matchesRegex;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.destroyVM;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;

/**
 * Test suite that exercises the DHCP options configuration across a LAN.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/24/11
 */
public class BridgeDHCPTest {

    private final static Logger log = LoggerFactory.getLogger(
        BridgeDHCPTest.class);

    static Tenant tenant;
    static TapWrapper tapPort;

    static MidolmanMgmt mgmt;
    static MidolmanLauncher midolman;
    static OvsBridge ovsBridge;
    static Bridge bridge;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String tapPortName = "brDhcpTestTap";
    static String vmHostName = "br-dhcp-test-host";

    static VMController vm;

    static LockHelper.Lock lock;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1",
                                                      12344);
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br");
        mgmt = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start("BridgeDHCPTest");

        tenant = new Tenant.Builder(mgmt).setName("tenant-br-dhcp").build();

        bridge = tenant.addBridge().setName("bridge1").build();

        BridgePort vmPort = bridge.addPort().build();
        tapPort = new TapWrapper(tapPortName);
        ovsBridge.addSystemPort(vmPort.getId(), tapPortName);

        BridgePort internalPort = bridge.addPort().build();

        IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
        ovsBridge.addInternalPort(internalPort.getId(),
                                  "brDhcpPortInt", ip2, 24);

        tapPort.closeFd();
        Thread.sleep(1000);

        LibvirtHandler handler =
            LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template_x86_64");

        vm = handler.newDomain()
                    .setDomainName("test_br_dhcp")
                    .setHostName(vmHostName)
                    .setNetworkDevice(tapPort.getName())
                    .build();

        Subnet subnet = bridge.newDhcpSubnet()
                              .havingSubnet("192.168.231.1", 24)
                              .havingGateway("192.168.231.3")
                              .build();

        subnet.newHostMapping("192.168.231.10",
                              vm.getNetworkMacAddress())
              .build();
    }

    @AfterClass
    public static void tearDown() {
        destroyVM(vm);

        removeTapWrapper(tapPort);
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant);
        //stopMidolmanMgmt(mgmt);

        lock.release();
    }

    @Before
    public void startVm() throws InterruptedException {
        vm.startup();
        sleepBecause("Give the machine a little bit of time to bootup", 5);
    }

    @After
    public void shutdownVm() throws Exception {
        vm.shutdown();
        waitFor("the VM to shutdown", TimeUnit.SECONDS.toMillis(3), 50,
                new Timed.Execution<Object>() {
                    @Override
                    protected void _runOnce() throws Exception {
                        setCompleted(!vm.isRunning());
                    }
                });
    }

    @Test
    public void testSampleTest() throws Exception {

        assertThatCommandOutput("the host should have our chosen name",
                                "hostname", is(vmHostName));

    }

    @Test
    public void testMtu() throws Exception {
        assertThatCommandOutput("displays the new MTU value",
                                "ip link show eth0 | grep mtu",
                                matchesRegex(".+mtu 1450.+"));
    }

    protected void assertThatCommandOutput(String message,
                                           final String command,
                                           final Matcher<String> resultMatcher)
        throws Exception {
        String commandResult =
            waitFor("successful remote execution of: " + command,
                    TimeUnit.MINUTES.toMillis(2), 50,
                    new Timed.Execution<String>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            try {
                                String result =
                                    SshHelper.newRemoteCommand(command)
                                             .onHost("192.168.231.10")
                                             .withCredentials(
                                                 "ubuntu", "ubuntu")
                                             .run((int) getRemainingTime());

                                setResult(result.trim());
                                setCompleted(resultMatcher.matches(getResult()));
                            } catch (IOException e) {
                                // try again later
                            }
                        }
                    });

        assertThat(message, commandResult, resultMatcher);
    }

}
