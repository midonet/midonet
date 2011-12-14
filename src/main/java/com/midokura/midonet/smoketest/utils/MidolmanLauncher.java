/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.utils;

import static com.midokura.tools.process.ProcessHelper.newProcess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MidolmanLauncher {

    private static final String[] MIDOLMANJ_CMD = { "java", "-cp",
            "./conf:/usr/share/midolman/midolmanj.jar", "-Dmidolman.log.dir=.",
            "com.midokura.midolman.Midolman", "-c" };
    private static final String conf1 = "./conf/midolman.conf";
    private static final String conf2 = "./conf/midolman2.conf";
    // There are cases where this doesn't prevent multiple executions of
    // start method's code. But for test suites this should be fine.
    private static boolean started = false;

    public synchronized static void start() throws IOException {
        if (started)
            return;
        started = true;
        List<String> cmd = new ArrayList<String>(Arrays.asList(MIDOLMANJ_CMD));
        // Start MM1
        cmd.add(conf1);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.start();
        // Start MM2
        cmd.set(cmd.size()-1, conf2);
        pb = new ProcessBuilder(cmd);
        pb.start();
    }
}
