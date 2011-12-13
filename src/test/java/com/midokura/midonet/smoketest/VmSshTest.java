/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.Tenant;
import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import com.midokura.tools.ssh.SshHelper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.midokura.tools.process.ProcessHelper.newProcess;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 12:16 PM
 */
public class VmSshTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(VmSshTest.class);

    static Tenant tenant;
    static TapPort tapPort;
    static InternalPort internalPort;
    static MidolmanMgmt mgmt;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String tapPortName = "tapPort1";

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                         "127.0.0.1",
                                                         12344);
        mgmt = new MockMidolmanMgmt(false);

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();

        Router router = tenant.addRouter().setName("rtr1").build();

        tapPort = router.addPort(ovsdb)
                      .setDestination("192.168.100.2")
                      .setOVSPortName(tapPortName)
                      .buildTap();

        internalPort = router.addPort(ovsdb)
                           .setDestination("192.168.100.3")
                           .buildInternal();

//        newProcess(String.format("sudo -n route add -net 192.168.100.0/24 " +
//                                     "dev" +
//                                     " %s", internalPort.getName()))
        newProcess("sudo -n route add -net 192.168.100.0/24 via 192.168.100.3")
            .logOutput(log, "add_host_route")
            .runAndWait();

        tapPort.closeFd();
        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        // First clean up left-overs from previous incomplete tests.
        removePort(tapPort);
        removeTenant(tenant);
        mgmt.stop();

        ovsdb.delBridge("smoke-br");

        resetZooKeeperState(log);
    }

    @Test
    public void test() throws IOException, InterruptedException {

        LibvirtHandler handler =
            LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template_x86_64");

        VMController vm =
            handler.newDomain()
                .setDomainName("test_ssh_domain")
                .setHostName("test")
                .setNetworkDevice(tapPort.getName())
                .build();

        try {
            vm.startup();

            assertThat("The Machine should have been started", vm.isRunning());
            log.info("Running remote command to find the hostname.");
            // validate ssh to the 192.168.100.2 address
            String output =
                SshHelper.newRemoteCommand("hostname")
                    .onHost("192.168.100.2")
                    .withCredentials("ubuntu", "ubuntu")
                    .runWithTimeout(60 * 1000); // 60 seconds

            log.info("Command output: {}", output.trim());

            // validate that the hostname of the target VM matches the
            // hostname that we configured for the vm
            assertThat("The remote hostname command output should match the " +
                           "hostname we chose for the VM",
                          output.trim(), equalTo(vm.getHostName()));

        } finally {
            vm.shutdown();
            vm.destroy();
        }
    }
}
