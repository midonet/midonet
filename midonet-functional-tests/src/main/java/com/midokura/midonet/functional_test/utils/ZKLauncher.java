/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midonet.functional_test.utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.process.ProcessHelper;

/**
 * Date: 5/31/12
 */
public class ZKLauncher {

    private final static Logger log = LoggerFactory
            .getLogger(ZKLauncher.class);

    public static final String ZK_SERVER_FILE_PATH = "/usr/sbin/zkServer.sh";
    static final String ZK_JMX_VARIABLES = "JVMFLAGS";
    static final String ZK_JMX_LOCAL = "JMXLOCALONLY";
    static final String CONF_FILE_DIR = "midonet-functional-tests/midolmanj_runtime_configurations";
    static final String CONF_FILE_NAME = "zookeeper.conf";
    private Process zkProcess;
    private Thread zkProcessShutdownHook;


    /**
     * This will try to return the pid of the process managed by this instance.
     *
     * @return the pid of the process or -1 if there is no process
     */
    public int getPid() {
        return ProcessHelper.getProcessPid(zkProcess);
    }

    public enum ConfigType {
        Default, Jmx_Enabled
    }

    public static ZKLauncher start(ConfigType configType, String JmxPort)
            throws IOException, InterruptedException {

        if(!(new File(ZK_SERVER_FILE_PATH).exists()))
        {
            log.error("Unable to find {}", ZK_SERVER_FILE_PATH);
            throw new RuntimeException("Unable to start Zookeeper!");
        }

        ZKLauncher launcher = new ZKLauncher();

        launcher.startZk(configType.name(), JmxPort);

        return launcher;
    }

    private void startZk(String configType, String JmxPort)
            throws IOException, InterruptedException {

        String cmdLine = ZK_SERVER_FILE_PATH + " start " + CONF_FILE_DIR + "/" + CONF_FILE_NAME;
        Map<String, String> envVars = new HashMap<String, String>();
        if(configType.equals(ConfigType.Jmx_Enabled.name())){
            // enable JMX locally only
            envVars.put(ZK_JMX_LOCAL, "true");
            String jmxParam = " -Dcom.sun.management.jmxremote.port=";
            jmxParam += JmxPort;
            jmxParam +=" -Dcom.sun.management.jmxremote.authenticate=false" +
                " -Dcom.sun.management.jmxremote.ssl=false'";
            envVars.put(ZK_JMX_VARIABLES, jmxParam);
        }
        zkProcess = ProcessHelper
                .newLocalProcess(cmdLine)
                .setEnvVariables(envVars)
                .run();

        zkProcessShutdownHook = new Thread() {
            @Override
            public void run() {
                synchronized (ZKLauncher.this) {
                    ZKLauncher.this.zkProcessShutdownHook = null;
                    ZKLauncher.this.stop();
                }
            }
        };

        Runtime.getRuntime().addShutdownHook(zkProcessShutdownHook);
    }


    public synchronized void stop() {
        if (null != zkProcess) {
            String cmdLineStop = ZK_SERVER_FILE_PATH + " stop " + CONF_FILE_DIR + "/" + CONF_FILE_NAME;
            ProcessHelper
                .newLocalProcess(cmdLineStop)
                .runAndWait();
            zkProcess = null;
        }
    }
}
