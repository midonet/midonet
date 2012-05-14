/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static java.lang.String.format;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midolman.util.Sudo;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.RouterPort;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.utils.RemoteTap;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.tools.timed.Timed;
import com.midokura.util.SystemHelper;
import com.midokura.util.process.ProcessHelper;
import static com.midokura.tools.timed.Timed.newTimedExecution;

/**
 * @author Mihai Claudiu Toader  <mtoader@midokura.com>
 *         Date: 12/7/11
 */
public class FunctionalTestsHelper {

    protected final static Logger log = LoggerFactory
        .getLogger(FunctionalTestsHelper.class);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    cleanupZooKeeperData();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    protected static void cleanupZooKeeperData()
        throws IOException, InterruptedException {
        // Often zkCli.sh is not in the PATH, use the one from default install
        // otherwise

        String zkClient;
        SystemHelper.OsType osType = SystemHelper.getOsType();

        switch (osType) {
            case Mac:
                zkClient = "zkCli";
                break;
            case Linux: case Unix: case Solaris:
                zkClient = "zkCli.sh";
                break;
            default:
                zkClient = "zkCli.sh";
                break;
        }

        List<String> pathList =
            ProcessHelper.executeLocalCommandLine("which " + zkClient);

        if (pathList.isEmpty()) {
            switch (osType) {
                case Mac:
                    zkClient = "/usr/local/bin/zkCli";
                    break;
                default:
                    zkClient = "/usr/share/zookeeper/bin/zkCli.sh";
            }
        }

        //TODO(pino, mtoader): try removing the ZK directory without restarting
        //TODO:     ZK. If it fails, stop/start/remove, to force the remove,
        //TODO      then throw an error to identify the bad test.

        int exitCode = ProcessHelper
            .newLocalProcess(zkClient + " -server 127.0.0.1:2181 rmr /smoketest")
            .logOutput(log, "cleaning_zk")
            .runAndWait();

	if (exitCode != 0 && SystemHelper.getOsType() == SystemHelper.OsType.Linux) {
	    // Restart ZK to get around the bug where a directory cannot be deleted.
            Sudo.sudoExec("service zookeeper stop");
	        Sudo.sudoExec("service zookeeper start");

            // Now delete the functional test ZK directory.
            ProcessHelper
                .newLocalProcess(zkClient + " -server 127.0.0.1:2181 rmr /smoketest")
                .logOutput(log, "cleaning_zk")
                .runAndWait();
         }
    }

    protected static void removeRemoteTap(RemoteTap tap) {
        if (tap != null) {
            try {
                tap.remove();
            } catch (Exception e) {
                log.error("While trying to remote a remote tap", e);
            }
        }
    }

    protected static void removeTapWrapper(TapWrapper tap) {
        if (tap != null) {
            tap.remove();
        }
    }

    public static void fixQuaggaFolderPermissions()
        throws IOException, InterruptedException {
        // sometimes after a reboot someone will reset the permissions which in
        // turn will make our Zebra implementation unable to bind to the socket
        // so we fix it like a boss.
        ProcessHelper
            .newProcess("chmod 777 /var/run/quagga")
            .withSudo()
            .runAndWait();
    }

    protected void removeMidoPort(RouterPort port) {
        if (port != null) {
            port.delete();
        }
    }

    public static void removeTenant(Tenant tenant) {
        if (null != tenant)
            tenant.delete();
    }

    public static void stopMidolman(MidolmanLauncher mm) {
        if (null != mm)
            mm.stop();
    }

    public static void stopMidolmanMgmt(MidolmanMgmt mgmt) {
        if (null != mgmt)
            mgmt.stop();
    }

    public static void removeBridge(OvsBridge ovsBridge) {
        if (ovsBridge != null) {
            ovsBridge.remove();
        }
    }

    protected static <T> T waitFor(String what, Timed.Execution<T> assertion)
        throws Exception {
        return waitFor(what,
                       TimeUnit.SECONDS.toMillis(10),
                       TimeUnit.MILLISECONDS.toMillis(500),
                       assertion);
    }

    public static <T> T waitFor(String what, long total, long between,
                                Timed.Execution<T> assertion)
        throws Exception {
        long start = System.currentTimeMillis();
        Timed.ExecutionResult<T> executionResult =
            newTimedExecution()
                .until(total)
                .waiting(between)
                .execute(assertion);

        assertThat(
            String.format("The wait for: \"%s\" didn't complete successfully " +
                              "(waited %d seconds)", what,
                          (System.currentTimeMillis() - start ) / 1000),
            executionResult.completed());

        return executionResult.result();
    }

    public static void waitForBridgeToConnect(
        final ServiceController controller)
        throws Exception {

        waitFor(
            "waiting for the bridge to connect to the controller on port: " +
                controller.getPortNum(),
            new Timed.Execution<Boolean>() {
                @Override
                protected void _runOnce() throws Exception {
                    setResult(controller.isConnected());
                    setCompleted(getResult());
                }
            });
    }

    public static void removeVpn(MidolmanMgmt mgmt, RouterPort vpn1) {
        if (mgmt != null && vpn1 != null) {
            mgmt.deleteVpn(vpn1.getVpn());
        }
    }

    public static void sleepBecause(String condition, long seconds)
        throws InterruptedException {
        log.debug(
            format("Sleeping %d seconds because: \"%s\"", seconds, condition));

        TimeUnit.SECONDS.sleep(seconds);

        log.debug(
            format("Sleeping done: \"%s\"", condition));
    }

    public static void destroyVM(VMController vm) {
        try {
            if (vm != null) {
                vm.destroy();
            }

            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //
        }
    }

    public static void assertNoMorePacketsOnTap(TapWrapper tapWrapper) {
        assertThat(
            format("Got an unexpected packet from tap %s", tapWrapper.getName()),
            tapWrapper.recv(), nullValue());
    }

    public static void assertNoMorePacketsOnTap(RemoteTap tap) {
        assertThat(
            format("Got an unexpected packet from tap %s", tap.getName()),
            tap.recv(), nullValue());
    }

    public static void assertPacketWasSentOnTap(TapWrapper tap,
                                                byte[] packet) {
        assertThat(
            format("We couldn't send a packet via tap %s", tap.getName()),
            tap.send(packet));

    }
}
