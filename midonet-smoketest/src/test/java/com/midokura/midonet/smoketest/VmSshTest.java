/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.smoketest;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.MidoPort;
import com.midokura.midonet.smoketest.topology.OvsBridge;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.TapWrapper;
import com.midokura.midonet.smoketest.topology.Tenant;
import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import com.midokura.tools.ssh.SshHelper;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 12:16 PM
 */
@Ignore
public class VmSshTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(VmSshTest.class);

    static Tenant tenant;
    static TapWrapper tapPort;

    static MidolmanMgmt mgmt;
    static OvsBridge ovsBridge;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String tapPortName = "vmSshTestTap1";
    static VMController vm;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1",
                                                      12344);
        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);
        mgmt = new MockMidolmanMgmt(false);

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();

        Router router = tenant.addRouter().setName("rtr1").build();

        IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
        MidoPort p1 = router.addVmPort().setVMAddress(ip1).build();
        tapPort = new TapWrapper(tapPortName);
        ovsBridge.addSystemPort(p1.port.getId(), tapPortName);

        IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");
        MidoPort p2 = router.addVmPort().setVMAddress(ip2).build();
        ovsBridge.addInternalPort(p2.port.getId(), "vmSshTestInt", ip2, 24);

        tapPort.closeFd();
        Thread.sleep(1000);

        LibvirtHandler handler =
            LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template_x86_64");

        vm = handler.newDomain()
                    .setDomainName("test_ssh_domain")
                    .setHostName("test")
                    .setNetworkDevice(tapPort.getName())
                    .build();

    }

    @AfterClass
    public static void tearDown() {
        ovsdb.delBridge("smoke-br");

        removeTapWrapper(tapPort);
        removeTenant(tenant);
        mgmt.stop();

        resetZooKeeperState(log);
    }

    @Test
    public void testSshRemoteCommand() throws IOException, InterruptedException {

        try {
            vm.startup();

            assertThat("The Machine should have been started", vm.isRunning());
            log.info("Running remote command to find the hostname.");
            // validate ssh to the 192.168.231.2 address
            String output =
                SshHelper.newRemoteCommand("hostname")
                    .onHost("192.168.231.2")
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
        }
    }

    @Test
    public void testScp() throws IOException, InterruptedException {

        try {
            vm.startup();

            assertThat("The Machine should have been started", vm.isRunning());

            String output =
                SshHelper.newRemoteCommand("cat test_file.txt 2>/dev/null")
                         .onHost("192.168.231.2")
                         .withCredentials("ubuntu", "ubuntu")
                         .runWithTimeout(60 * 1000); // 60 seconds
            
            assertThat("There should not by any content in the target test_file.txt", 
                       output, equalTo(""));

            File localFile = File.createTempFile("smoke-ssh-test", null);
            localFile.deleteOnExit();

            FileUtils.writeStringToFile(localFile, "Hannibal");

            SshHelper.copyFileTo(localFile.getAbsolutePath(), "test_file.txt")
                     .onHost("192.168.231.2")
                     .withCredentials("ubuntu", "ubuntu")
                     .runWithTimeout(60 * 1000); // 60 seconds

            output =
                SshHelper.newRemoteCommand("cat test_file.txt 2>/dev/null")
                         .onHost("192.168.231.2")
                         .withCredentials("ubuntu", "ubuntu")
                         .runWithTimeout(60 * 1000); // 60 seconds

            assertThat("The remote file should have our content.",
                       output, equalTo("Hannibal"));

            File newLocalFile = File.createTempFile("smoke-ssh-test", null);
            SshHelper.copyFileFrom(newLocalFile.getAbsolutePath(), "test_file.txt")
                     .onHost("192.168.231.2")
                     .withCredentials("ubuntu", "ubuntu")
                     .runWithTimeout(60 * 1000); // 60 seconds

            output = FileUtils.readFileToString(newLocalFile);
            assertThat("The remote copy to local file should have succeeded. The file content check failed.",
                       output, equalTo("Hannibal"));

        } finally {
            vm.shutdown();
        }
    }
}
