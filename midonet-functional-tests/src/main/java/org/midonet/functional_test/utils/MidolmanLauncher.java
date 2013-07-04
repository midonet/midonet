/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static java.lang.String.format;

import org.apache.commons.lang.StringUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.process.DrainTargets;
import org.midonet.util.process.ProcessHelper;

public class MidolmanLauncher {

    private final static Logger log = LoggerFactory
            .getLogger(MidolmanLauncher.class);

    public static final String MIDONET_PROJECT_LOCATION =
            "midonet.functional.tests.current_midonet_project";

    public static final String MIDONET_MIDOLMAN_USE_JARS =
            "midonet.functional.tests.use_midolman_jars";

    private Process midolmanProcess;
    private Thread midolmanProcessShutdownHook;

    /**
     * This will try to return the pid of the process managed by this instance.
     *
     * @return the pid of the process or -1 if there is no process
     */
    public int getPid() {
        return ProcessHelper.getProcessPid(midolmanProcess);
    }

    public enum ConfigType {
        Default, Without_Bgp, With_Bgp, With_Node_Agent, Monitoring
    }

    public static MidolmanLauncher start(String logPostFix) throws IOException {
        return start(ConfigType.Default, logPostFix);
    }

    public static MidolmanLauncher start(ConfigType configType, String logMark)
            throws IOException {

        MidolmanLauncher launcher = new MidolmanLauncher();

        launcher.startMidolman(configType.name().toLowerCase(), logMark);

        return launcher;
    }

    private void startMidolman(String configType, String logFilePostfix) {
        String commandLine = createCommandLine(configType, logFilePostfix);
        log.debug("Launching midolman with command line: {}", commandLine);
        log.debug("Launching midolman from folder: {}",
                new File(".").getAbsolutePath());

        midolmanProcess = ProcessHelper
                .newLocalProcess(commandLine)
               // .setDrainTarget(DrainTargets.noneTarget())
                .setDrainTarget(DrainTargets.slf4jTarget(log, "<midolman>"))
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

        return format(
            "sudo java " +
            "-Xbootclasspath/p:../midonet-jdk-bootstrap/target/" +
                "midonet-jdk-bootstrap-1.2.0-SNAPSHOT.jar " +
            " -Djava.library.path=%s " +
            "-cp %s -Dmidolman.log.file=%s org.midonet.midolman.Midolman " +
            "-c %s",
            getLibraryPath(),
            getClassPath(),
            getLogFilePath(configType, logPostfix),
            format(
                "midolman_runtime_configurations/midolman-%s.conf",
                configType));
    }

    private String getLibraryPath() {
        String libraryPath = System.getProperty("java.library.path");
        String midonetLocation =
            System.getProperty(MIDONET_PROJECT_LOCATION, "");

        if (!midonetLocation.equals(""))
            return String.format("%s:%s/midolman/lib-native", libraryPath, midonetLocation);
        else
            return libraryPath;
    }

    private String getLogFilePath(String configType, String postfix) {
        String fileName =
                format("midolman-%s%s.log",
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
            return "/usr/share/midolman/midolman.jar";
        }

        List<String> classPathEntries = new ArrayList<String>();

        // add the log.xml file location first in the class path
        addEntry(classPathEntries,
                new File(midonetLocation,
                        "midonet-functional-tests/midolman_runtime_configurations"));

        // add either the classes or the built jars of the project dependencies
        if (!Boolean.getBoolean(MIDONET_MIDOLMAN_USE_JARS)) {
            addEntry(classPathEntries,
                    new File(midonetLocation, "midolman/target/classes"));

            addEntry(classPathEntries,
                    new File(midonetLocation, "midonet-util/target/classes"));
        } else {
            // TODO: (mtoader@midokura.com) Fix this by locating the current
            // version from a file dumped by maven at the build time.
            addEntry(classPathEntries,
                    new File(midonetLocation,
                            "midolman/target/midolman-1.2.0-SNAPSHOT.jar"));

            addEntry(classPathEntries,
                    new File(midonetLocation,
                            "midonet-util/target/midonet-util-1.2.0-SNAPSHOT" +
                                ".jar"));
        }

        // add all the midolman dependencies that we find inside the
        // target/dependencies folder (check the midolman/pom.xml to see how
        // they are copied in there)
        File[] midolmanJars =
                new File(midonetFolder, "midolman/target/dep").listFiles(
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
        //Don't stop it because it cannot be restarted (bug in stop method).
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
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
