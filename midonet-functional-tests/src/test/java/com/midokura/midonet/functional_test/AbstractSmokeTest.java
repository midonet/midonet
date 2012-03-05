/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;

import com.midokura.midolman.util.Sudo;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.MidoPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.tools.timed.Timed;
import com.midokura.util.process.ProcessHelper;
import static com.midokura.tools.timed.Timed.newTimedExecution;

/**
 * @author Mihai Claudiu Toader  <mtoader@midokura.com>
 *         Date: 12/7/11
 */
public abstract class AbstractSmokeTest {

    private final static Logger log = LoggerFactory
        .getLogger(AbstractSmokeTest.class);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                cleanupZooKeeperData();
            }
        });
    }

    protected static void cleanupZooKeeperData() {
        ProcessHelper
            .newProcess("zkCli.sh -server 127.0.0.1:2181 rmr " +
                            " /smoketest")
            .logOutput(log, "cleaning_zk")
            .runAndWait();
    }

    protected static void removeTapWrapper(TapWrapper tap) {
        if (tap != null) {
            tap.remove();
        }
    }

    protected static void fixQuaggaFolderPermissions()
        throws IOException, InterruptedException {
        // sometimes after a reboot someone will reset the permissions which in
        // turn will make our Zebra implementation unable to bind to the socket
        // so we fix it like a boss.
        Sudo.sudoExec("chmod 777 /run/quagga");
    }

    protected void removeMidoPort(MidoPort port) {
        if (port != null) {
            port.delete();
        }
    }

    protected static void removeTenant(Tenant tenant) {
        if (null != tenant)
            tenant.delete();
    }

    protected static void stopMidolman(MidolmanLauncher mm) {
        if (null != mm)
            mm.stop();
    }

    protected static void stopMidolmanMgmt(MidolmanMgmt mgmt) {
        if (null != mgmt)
            mgmt.stop();
    }

    protected static void removeBridge(OvsBridge ovsBridge) {
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

    protected static <T> T waitFor(String what, long total, long between,
                                   Timed.Execution<T> assertion)
        throws Exception {
        Timed.ExecutionResult<T> executionResult =
            newTimedExecution()
                .until(total)
                .waiting(between)
                .execute(assertion);

        assertThat(
            String.format("The wait for: \"%s\" didn't complete successfully " +
                              "(waited %d seconds)", what, total % 1000),
            executionResult.completed());

        return executionResult.result();
    }

    protected static void waitForBridgeToConnect(
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

    protected void removeVpn(MidolmanMgmt mgmt, MidoPort vpn1) {
        if (mgmt != null && vpn1 != null) {
            mgmt.deleteVpn(vpn1.getVpn());
        }
    }
}
