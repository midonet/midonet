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

    public static final String MIDONET_PROJECT_LOCATION =
        "midonet.functional.tests.current_midonet_project";

    public static final String MIDONET_MIDOLMAN_USE_JARS =
        "midonet.functional.tests.use_midolman_jars";

    private Process midolmanProcess;
    private Thread midolmanProcessShutdownHook;

    public enum ConfigType {
        Default, Without_Bgp, With_Node_Agent
    }

    public static MidolmanLauncher start(String logPostFix) throws IOException {
        return start(ConfigType.Default, logPostFix);
    }

    public static MidolmanLauncher start(ConfigType configType, String logPostfix)
        throws IOException {

        MidolmanLauncher launcher = new MidolmanLauncher();

        launcher.startMidolman(configType.name().toLowerCase(), logPostfix);

        return launcher;
    }

    private void startMidolman(String configType, String logFilePostfix) {
        String commandLine = createCommandLine(configType, logFilePostfix);
        log.debug("Launching midolman with command line: " + commandLine);
        log.debug("Launching midolman from folder: " + new File(".").getAbsolutePath());

        midolmanProcess = ProcessHelper
            .newProcess(commandLine)
            .setDrainTarget(DrainTargets.noneTarget())
//            .setDrainTarget(DrainTargets.slf4jTarget(log, "<midolman>"))
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

    private String createCommandLine(String configType, String logPostfix) {
        return
            String.format(
                "java -cp %s " +
                    "-Dmidolman.log.file=%s " +
                    "com.midokura.midolman.Midolman " +
                    "-c %s",
                getClassPath(),
                getLogFilePath(configType, logPostfix),
                String.format("midolmanj_runtime_configurations/midolman-%s.conf",
                              configType));
    }

    private String getLogFilePath(String configType, String postfix) {
        String fileName =
            String.format("midolman-%s%s.log",
                          configType,
                          postfix != null ? "-" + postfix : "");

        String midonetLocation =
            System.getProperty(MIDONET_PROJECT_LOCATION, "");

        File midonetFolder = new File(midonetLocation);
        if (!midonetFolder.exists() || !midonetFolder.isDirectory()) {
            return "./midonet-functional-tests/target/" + fileName;
        }

        return "./target/" + fileName;
    }

    private String getClassPath() {
        String midonetLocation =
            System.getProperty(MIDONET_PROJECT_LOCATION, "");

        File midonetFolder = new File(midonetLocation);
        if (!midonetFolder.exists() || !midonetFolder.isDirectory()) {
            return "/usr/share/midolman/midolmanj.jar";
        }

        List<String> classPathEntries = new ArrayList<String>();

        // add the log.xml file location first in the class path
        addEntry(classPathEntries,
                new File(midonetLocation, "midonet-functional-tests/midolmanj_runtime_configurations"));

        // add either the classes or the built jars of the project dependencies
        if (!Boolean.getBoolean(MIDONET_MIDOLMAN_USE_JARS) ) {
            addEntry(classPathEntries,
                    new File(midonetLocation, "midolmanj/target/classes"));

            addEntry(classPathEntries,
                    new File(midonetLocation, "midokura-util/target/classes"));
        } else {
            addEntry(classPathEntries,
                    new File(midonetLocation,
                            "midolmanj/target/midolmanj-12.06-SNAPSHOT.jar"));

            addEntry(classPathEntries,
                    new File(midonetLocation,
                    "midokura-util/target/midokura-util-12.06-SNAPSHOT.jar"));
        }

        // add all the midolmanj dependencies that we find inside the
        // target/dependencies folder (check the midolmanj/pom.xml to see how
        // they are copied in there)
        File []midolmanJars =
            new File(midonetFolder, "midolmanj/target/dep").listFiles(
                new FileFilter() {
                    @Override
                    public boolean accept(File pathName) {
                        return pathName.isFile() && pathName.getName()
                                                            .endsWith("jar");
                    }
                });

        for (File midolmanJar : midolmanJars) {
            addEntry(classPathEntries, midolmanJar);
        }

        return StringUtils.join(classPathEntries, ":");
    }

    private void addEntry(List<String> entries, File file) {
        try {
            entries.add(file.getCanonicalPath());
        } catch (IOException ex) {
            entries.add(file.getAbsolutePath());
        }
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
