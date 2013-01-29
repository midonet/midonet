/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midonet.functional_test.utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

import org.apache.commons.io.FileUtils;
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
    // for this to work the /etc/zookeeper/zookeeper-env.sh
    // need to have the line:
    //      export ZOO_LOG_DIR=/var/log/zookeeper
    // changed into:
    //      export ZOO_LOG_DIR=${ZOO_LOG_DIR:-/var/log/zookeeper}
    static final String ZK_LOG_DIR = "ZOO_LOG_DIR";

    static final String CONF_FILE_DIR = "midolman_runtime_configurations";
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
        Default(2182, -1), Jmx_Enabled(2182, 12222);

        int port;
        int jmxPort;

        private ConfigType(int port, int jmxPort) {
            this.port = port;
            this.jmxPort = jmxPort;
        }

        public int getPort() {
            return port;
        }

        public int getJmxPort() {
            return jmxPort;
        }
    }

    public static ZKLauncher start(ConfigType configType)
        throws IOException, InterruptedException {

        ZKLauncher launcher = new ZKLauncher();

        launcher.launch(configType);

        return launcher;
    }

    private void launch(ConfigType configType)
        throws IOException, InterruptedException {

        File confFile = new File(CONF_FILE_DIR + "/" + CONF_FILE_NAME);

        String cmdLine =
            format("%s %s %s",
                   getZkBinaryByOs(),
                   "start",
                   confFile.getAbsolutePath());

        Map<String, String> env = new HashMap<String, String>();
        env.put(ZK_LOG_DIR, "/tmp/zk/log");

        if (configType.getJmxPort() != -1) {
            // enable JMX locally only
            env.put(ZK_JMX_LOCAL, "true");
            env.put(ZK_JMX_VARIABLES,
                        format("-Dcom.sun.management.jmxremote.port=%d" +
                                   " -Dcom.sun.management.jmxremote.authenticate=false" +
                                   " -Dcom.sun.management.jmxremote.ssl=false",
                               configType.getJmxPort()));
        }

        if ( new File("/tmp/zk/log/").mkdirs() ) {
            log.error("Error creating temporary zookeeper store directories");
        }

        int retcode = ProcessHelper
            .newLocalProcess(cmdLine)
            .setEnvVariables(env)
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

        try {
            FileUtils.deleteDirectory(new File("/tmp/zk"));
        } catch (IOException e) {
            log.error("Failed to remove zookeeper temporary folder.", e);
        }
    }
}
