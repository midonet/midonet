/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midonet.functional_test.utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.SystemHelper;
import com.midokura.util.process.ProcessHelper;

/**
 * Date: 5/31/12
 */
public class ZKLauncher {

    private final static Logger log = LoggerFactory
        .getLogger(ZKLauncher.class);

    public static final String ZK_SERVER_PATH_LINUX = "/usr/sbin/zkServer.sh";
    public static final String ZK_SERVER_PATH_MACOS = "/usr/local/bin/zkServer";

    static final String ZK_JMX_VARIABLES = "JVMFLAGS";
    static final String ZK_JMX_LOCAL = "JMXLOCALONLY";
    static final String CONF_FILE_DIR = "midolmanj_runtime_configurations";
    static final String CONF_FILE_NAME = "zookeeper.conf";

    public String getZkBinaryByOs() {
        switch (SystemHelper.getOsType()) {
            case Mac:
                return ZK_SERVER_PATH_MACOS;

            default:
                return ZK_SERVER_PATH_LINUX;
        }
    }

    public enum ConfigType {
        Default, Jmx_Enabled
    }

    public static ZKLauncher start(ConfigType configType, int JmxPort)
        throws IOException, InterruptedException {

        ZKLauncher launcher = new ZKLauncher();

        launcher.startZk(configType, JmxPort);

        return launcher;
    }

    private void startZk(ConfigType configType, int JmxPort)
        throws IOException, InterruptedException {

        File confFile = new File(CONF_FILE_DIR + "/" + CONF_FILE_NAME);

        String cmdLine =
            format("%s %s %s",
                   getZkBinaryByOs(),
                   "start",
                   confFile.getAbsolutePath());

        Map<String, String> envVars = new HashMap<String, String>();
        if (configType == ConfigType.Jmx_Enabled) {
            // enable JMX locally only
            envVars.put(ZK_JMX_LOCAL, "true");
            envVars.put(
                ZK_JMX_VARIABLES,
                format("-Dcom.sun.management.jmxremote.port=%d" +
                           " -Dcom.sun.management.jmxremote.authenticate=false" +
                           " -Dcom.sun.management.jmxremote.ssl=false",
                       JmxPort));
        }

        int retcode = ProcessHelper
            .newLocalProcess(cmdLine)
            .setEnvVariables(envVars)
            .logOutput(log, "<zookeeper>", ProcessHelper.OutputStreams.StdError)
            .runAndWait();

        if (retcode != 0) {
            throw new RuntimeException("Zookeeper didn't start!");
        }
    }

    public synchronized void stop() {

        String stopCommand =
            format("%s stop %s", getZkBinaryByOs(), new File(
                CONF_FILE_DIR + "/" + CONF_FILE_NAME).getAbsolutePath());

        ProcessHelper
            .newLocalProcess(stopCommand)
            .logOutput(log, "<zookeeper-stop>",
                       ProcessHelper.OutputStreams.StdError)
            .runAndWait();
    }
}
