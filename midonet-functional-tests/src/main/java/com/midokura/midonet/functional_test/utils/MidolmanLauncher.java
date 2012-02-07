/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.utils;

import com.midokura.tools.process.DrainTargets;
import com.midokura.tools.process.ProcessOutputDrainer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MidolmanLauncher {

    private final static Logger log = LoggerFactory
            .getLogger(MidolmanLauncher.class);
    private static final String[] MIDOLMANJ_CMD = {"java", "-cp", "CP_HERE",
            "-Dmidolman.log.dir=.", "com.midokura.midolman.Midolman", "-c",
            "CONF_HERE"};
    private static final String cp1 = "./conf:/usr/share/midolman/midolmanj.jar";
    private static final String cp2 = "./conf2:/usr/share/midolman/midolmanj.jar";
    private static final String conf1 = "./conf/midolman.conf";
    private static final String conf2 = "./conf2/midolman.conf";
    private Process m1;
    private Process m2;

    public static MidolmanLauncher start() throws IOException {
        return new MidolmanLauncher();
    }

    private MidolmanLauncher() throws IOException {
        List<String> cmd = new ArrayList<String>(Arrays.asList(MIDOLMANJ_CMD));
        // Start MM1
        cmd.set(2, cp1);
        cmd.set(6, conf1);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        m1 = pb.start();
        new ProcessOutputDrainer(m1, false)
                .drainOutput(DrainTargets.noneTarget(), false);

        // Start MM2
        cmd = new ArrayList<String>(Arrays.asList(MIDOLMANJ_CMD));
        // Start MM1
        cmd.set(2, cp2);
        cmd.set(6, conf2);
        pb = new ProcessBuilder(cmd);
        m2 = pb.start();
        new ProcessOutputDrainer(m2, false)
                .drainOutput(DrainTargets.noneTarget(), false);
    }

    public void stop() {
        if (null != m1) {
            m1.destroy();
            m1 = null;
        }
        if (null != m2) {
            m2.destroy();
            m2 = null;
        }
    }

    // TODO(rossella,pino) We should understand why the tests fail if we don't stop and restart
    // midolman for every test. Check redmine 590. Here is how the class was to start and stop
    // midolman just once during the tests execution
    /*    public class MidolmanLauncher {

    private final static Logger log = LoggerFactory
            .getLogger(MidolmanLauncher.class);
    private static final String[] MIDOLMANJ_CMD = { "java", "-cp", "CP_HERE",
            "-Dmidolman.log.dir=.", "com.midokura.midolman.Midolman", "-c",
            "CONF_HERE" };
    private static final String cp1 = "./conf:/usr/share/midolman/midolmanj.jar";
    private static final String cp2 = "./conf2:/usr/share/midolman/midolmanj.jar";
    private static final String conf1 = "./conf/midolman.conf";
    private static final String conf2 = "./conf2/midolman.conf";
    private static Process m1;
    private static Process m2;
    // There are cases where this doesn't prevent multiple executions of
    // start method's code. But for test suites this should be fine.
    private static boolean started = false;

    public synchronized static void start() throws IOException {
        if (started)
            return;
        started = true;
        List<String> cmd = new ArrayList<String>(Arrays.asList(MIDOLMANJ_CMD));
        // Start MM1
        cmd.set(2, cp1);
        cmd.set(6, conf1);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        m1 = pb.start();
        new ProcessOutputDrainer(m1, false)
            .drainOutput(DrainTargets.noneTarget(), false);

        // Start MM2
        cmd = new ArrayList<String>(Arrays.asList(MIDOLMANJ_CMD));
        // Start MM1
        cmd.set(2, cp2);
        cmd.set(6, conf2);
        pb = new ProcessBuilder(cmd);
        m2 = pb.start();

        new ProcessOutputDrainer(m2, false)
            .drainOutput(DrainTargets.noneTarget(), false);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                synchronized (MidolmanLauncher.class) {
                    log.warn("In shutdown hook: destroying Midolman controllers.");
                    if (null != m1) {
                        m1.destroy();
                        m1 = null;
                    }
                    if (null != m2) {
                        m2.destroy();
                        m2 = null;
                    }
                }
            }
        });
    }
    }*/

}
