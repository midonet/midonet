/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midonet.functional_test.utils;

import com.midokura.util.SystemHelper;
import com.midokura.util.process.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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

    public static ZKLauncher start(ConfigType configType, String JmxPort)
            throws IOException, InterruptedException {

        ZKLauncher launcher = new ZKLauncher();

        launcher.startZk(configType.name(), JmxPort);

        return launcher;
    }

    private void startZk(String configType, String JmxPort)
            throws IOException, InterruptedException {

        File confFile = new File(CONF_FILE_DIR + "/" + CONF_FILE_NAME);

        String cmdLine =
                String.format("%s %s %s",
                        getZkBinaryByOs(),
                        "start",
                        confFile.getAbsolutePath());

        Map<String, String> envVars = new HashMap<String, String>();
        if (configType.equals(ConfigType.Jmx_Enabled.name())) {
            // enable JMX locally only
            envVars.put(ZK_JMX_LOCAL, "true");
            String jmxParam = " -Dcom.sun.management.jmxremote.port=";
            jmxParam += JmxPort;
            jmxParam += " -Dcom.sun.management.jmxremote.authenticate=false" +
                    " -Dcom.sun.management.jmxremote.ssl=false'";
            envVars.put(ZK_JMX_VARIABLES, jmxParam);
        }
        int retcode = ProcessHelper
                .newLocalProcess(cmdLine)
                .setEnvVariables(envVars)
                .logOutput(log, "<zookeeper>", ProcessHelper.OutputStreams.StdError)
                .runAndWait();
        if (retcode != 0){
            throw new RuntimeException("Zookeeper didn't start!");
        }
    }

    public synchronized void stop() {

            String stopCommand =
                    String.format("%s stop %s", getZkBinaryByOs(), new File(CONF_FILE_DIR + "/" + CONF_FILE_NAME).getAbsolutePath());

            ProcessHelper
                    .newLocalProcess(stopCommand)
                    .logOutput(log, "<zookeeper-stop>", ProcessHelper.OutputStreams.StdError)
                    .runAndWait();
        }
    }
