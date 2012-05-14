/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.Subnet;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.vm.HypervisorType;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.midonet.functional_test.vm.libvirt.LibvirtHandler;
import com.midokura.util.ssh.SshHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.destroyVM;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;

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

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

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
    }

    @AfterClass
    public static void tearDown() {
        destroyVM(vm);

        removeTapWrapper(tapPort);
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant);
        stopMidolmanMgmt(mgmt);
    }

    @Ignore @Test
    public void testSampleTest() throws Exception {
        Subnet subnet = bridge.newDhcpSubnet()
                              .havingSubnet("192.168.231.1", 24)
                              .havingGateway("192.168.231.3")
                              .build();

        subnet.newHostMapping("192.168.231.10",
                              vm.getNetworkMacAddress())
              .build();

        vm.startup();

        sleepBecause("Give the machine a little bit of time to bootup", 10);

        String output =
                SshHelper.newRemoteCommand("hostname")
                         .onHost("192.168.231.10")
                         .withCredentials("ubuntu", "ubuntu")
                         .run((int)TimeUnit.SECONDS.toMillis(120));

        assertThat("We should have been able to connect and run a remote " +
                       "command on the machine",
                   output.trim(), equalTo(vmHostName));

        vm.shutdown();
    }
}
