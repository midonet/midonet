/*
 * Copyright 2011 Midokura Europe SARL
 */
package org.midonet.functional_test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.junit.Ignore;
import org.junit.Test;

import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.functional_test.vm.HypervisorType;
import org.midonet.functional_test.vm.VMController;
import org.midonet.functional_test.vm.libvirt.LibvirtHandler;
import org.midonet.util.ssh.SshHelper;
import org.midonet.util.ssh.commands.SshSession;


import static org.midonet.functional_test.FunctionalTestsHelper.destroyVM;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

@Ignore
public class VmSshTest extends TestBase {

    static TapWrapper tapPort;
    static String tapPortName = "vmSshTestTap1";
    static VMController vm;
    static SshSession sshSession;

    @Override
    public void setup() {
        Router router = apiClient.addRouter().name("rtr1").create();

        RouterPort p1 = router.addPort()
            .portAddress("192.168.231.1")
            .networkAddress("192.168.231.0").networkLength(24)
            .create();
        tapPort = new TapWrapper(tapPortName);
        //ovsBridge.addSystemPort(p1.port.getId(), tapPortName);

        RouterPort p2 = router.addPort()
            .portAddress("192.168.232.1")
            .networkAddress("192.168.232.0").networkLength(24)
            .create();
        //ovsBridge.addInternalPort(p2.port.getId(), "vmSshTestInt", ip2, 24);

        tapPort.closeFd();

        LibvirtHandler handler =
            LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template_x86_64");

        vm = handler.newDomain()
                    .setDomainName("test_ssh_domain")
                    .setHostName("test")
                    .setNetworkDevice(tapPort.getName())
                    .build();
    }

    @Override
    public void teardown() {
        removeTapWrapper(tapPort);
        destroyVM(vm);
    }

    @Test
    public void testSshRemoteCommand()
        throws IOException, InterruptedException {

        try {
            vm.startup();
            assertThat("The Machine should have been started", vm.isRunning());

            sshSession = SshHelper.newSession()
                              .onHost("192.168.231.2")
                              .withCredentials("ubuntu", "ubuntu")
                              .open(30*1000);

            log.info("Running remote command to find the hostname.");
            // validate ssh to the 192.168.231.2 address
            String output =
                SshHelper.newRemoteCommand("hostname")
                         .withSession(sshSession)
                         .run(30 * 1000); // 30 seconds

            log.info("Command output: {}", output.trim());

            // validate that the hostname of the target VM matches the
            // hostname that we configured for the vm
            assertThat("The remote hostname command output should match the " +
                           "hostname we chose for the VM",
                       output.trim(), equalTo(vm.getHostName()));

        } finally {
            if (sshSession != null) {
                sshSession.disconnect();
            }
            vm.shutdown();
        }
    }

    @Test
    public void testScp() throws Exception, InterruptedException {

        try {
            vm.startup();

            assertThat("The Machine should have been started", vm.isRunning());

            sshSession = SshHelper.newSession()
                              .onHost("192.168.231.2")
                              .withCredentials("ubuntu", "ubuntu")
                              .open(60*1000);

            String output =
                SshHelper.newRemoteCommand("cat test_file.txt 2>/dev/null")
                         .withSession(sshSession)
                         .run(30 * 1000); // 60 seconds

            assertThat(
                "There should not by any content in the target test_file.txt",
                output, equalTo(""));

            File localFile = File.createTempFile("smoke-ssh-test", null);
            //localFile.deleteOnExit();

            FileUtils.writeStringToFile(localFile, "Hannibal");

            int copyFileTimeout = (int) TimeUnit.SECONDS.toMillis(30);

            SshHelper.uploadFile(localFile.getAbsolutePath())
                     .toRemote("test_file.txt")
                     .usingSession(sshSession, copyFileTimeout);

            output =
                SshHelper.newRemoteCommand("cat test_file.txt 2>/dev/null")
                         .withSession(sshSession)
                         .run(60 * 1000); // 60 seconds

            assertThat("The remote file should have our content.",
                       output, equalTo("Hannibal"));

            File newLocalFile = File.createTempFile("smoke-ssh-test", null);
            SshHelper.getFile(newLocalFile.getAbsolutePath())
                     .fromRemote("test_file.txt")
                     .usingSession(sshSession, copyFileTimeout);

            output = FileUtils.readFileToString(newLocalFile);
            assertThat(
                "The remote copy to local file should have succeeded. The file content check failed.",
                output, equalTo("Hannibal"));

        } finally {
            if (sshSession != null) {
                sshSession.disconnect();
            }
            vm.shutdown();
        }
    }
}
