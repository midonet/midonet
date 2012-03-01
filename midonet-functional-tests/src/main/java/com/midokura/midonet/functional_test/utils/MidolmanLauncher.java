/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.process.DrainTargets;
import com.midokura.util.process.ProcessHelper;

public class MidolmanLauncher {

    private final static Logger log = LoggerFactory
        .getLogger(MidolmanLauncher.class);
    public static final String MIDONET_PROJECT_LOCATION = "midonet.functional.tests.current_midonet_project";

    private Process midolmanProcess;
    private Thread midolmanProcessShutdownHook;

    public final static String CONFIG_DEFAULT =
        "./midolmanj_runtime_configurations/default";

    public final static String CONFIG_WITHOUT_BGP =
        "./midolmanj_runtime_configurations/without_bgp";

    public final static String CONFIG_WITH_NODE_AGENT =
        "./midolmanj_runtime_configurations/with_node_agent";

    public static MidolmanLauncher start() throws IOException {
        return start(CONFIG_DEFAULT);
    }

    public static MidolmanLauncher start(String configFileLocation)
        throws IOException {

        MidolmanLauncher launcher = new MidolmanLauncher();

        launcher.startMidolman(configFileLocation);

        return launcher;
    }

    private void startMidolman(String configFileLocation) {
        String commandLine = createCommandLine(configFileLocation);
        log.debug("Launching midolman with command line: " + commandLine);
        midolmanProcess = ProcessHelper
            .newProcess(commandLine)
            .setDrainTarget(DrainTargets.noneTarget())
            .run();

        midolmanProcessShutdownHook = new Thread() {
            @Override
            public void run() {
                synchronized (MidolmanLauncher.this) {
                    MidolmanLauncher.this.midolmanProcessShutdownHook = null;
                    MidolmanLauncher.this.stop();
                }
            }
        };

        Runtime.getRuntime().addShutdownHook(midolmanProcessShutdownHook);
    }

    private String createCommandLine(String configFileLocation) {
        String classPath = getClassPath(configFileLocation);

        return
            String.format(
                "java -cp %s:%s -Dmidolman.log.dir=. " +
                    "com.midokura.midolman.Midolman -c %s/midolman.conf",
                configFileLocation, classPath, configFileLocation);
    }

    private String getClassPath(String configFileLocation) {
        String midonetLocation =
            System.getProperty(MIDONET_PROJECT_LOCATION, "");

        File midonetFolder = new File(midonetLocation);
        if (!midonetFolder.exists() || !midonetFolder.isDirectory()) {
            return "/usr/share/midolman/midolmanj.jar";
        }

        List<String> classPathEntries = new ArrayList<String>();

        classPathEntries.add(
            new File(midonetLocation,
                     "midolmanj/target/classes")
                .getAbsolutePath());

        classPathEntries.add(
            new File(midonetLocation,
                     "midokura-util/target/classes")
                .getAbsolutePath());

        classPathEntries.add(
            new File(midonetLocation,
                     "midolmanj/target/midolmanj-12.06-SNAPSHOT.jar")
                .getAbsolutePath());

        classPathEntries.add(
            new File(midonetLocation,
                     "midokura-util/target/midokura-util-12.06-SNAPSHOT.jar")
                .getAbsolutePath());


        File []midolmanJars =
            new File(midonetFolder, "midolmanj/target/dependencies").listFiles(
                new FileFilter() {
                    @Override
                    public boolean accept(File pathName) {
                        return pathName.isFile() && pathName.getName()
                                                            .endsWith("jar");
                    }
                });

        for (File midolmanJar : midolmanJars) {
            classPathEntries.add(midolmanJar.getAbsolutePath());
        }

        return StringUtils.join(classPathEntries, ":");
    }

    public synchronized void stop() {
        if (null != midolmanProcess) {
            ProcessHelper.killProcess(midolmanProcess);
            try {
                midolmanProcess.waitFor();
            } catch (InterruptedException e) {
                // wait the process to actually exit
            }
            midolmanProcess = null;
        }

        if (null != midolmanProcessShutdownHook) {
            Runtime.getRuntime()
                   .removeShutdownHook(midolmanProcessShutdownHook);
            midolmanProcessShutdownHook = null;
        }
    }
}
