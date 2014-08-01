/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.midolman;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.Properties;

import com.google.common.util.concurrent.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.midonet.midolman.guice.*;
import org.midonet.event.agent.ServiceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.services.MidostoreSetupService;

import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.datapath.DatapathModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.host.guice.HostModule;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.version.guice.VersionModule;

public class Midolman {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);
    static private final ServiceEvent serviceEvent = new ServiceEvent();

    static final int MIDOLMAN_ERROR_CODE_MISSING_CONFIG_FILE = 1;

    private Injector injector;

    private Midolman() {
    }

    /**
     * Exits by calling System.exits() with status code,
     * MIDOLMAN_ERROR_CODE_MISSING_CONFIG_FILE.
     *
     * @param configFilePath A path for the Midolman config file.
     */
    static void exitsMissingConfigFile(String configFilePath) {
        log.error("Midolman config file missing: " + configFilePath);
        log.error("Midolman exiting.");
        System.exit(MIDOLMAN_ERROR_CODE_MISSING_CONFIG_FILE);
    }

    private void run(String[] args) throws Exception {
        // log git commit info
        Properties properties = new Properties();
        properties.load(
            getClass().getClassLoader().getResourceAsStream("git.properties"));
        log.info("main start -------------------------");
        log.info("git describe: {}", properties.get("git.commit.id.describe"));
        log.info("branch: {}", properties.get("git.branch"));
        log.info("commit.time: {}", properties.get("git.commit.time"));
        log.info("commit.id: {}", properties.get("git.commit.id"));
        log.info("commit.user: {}", properties.get("git.commit.user.name"));
        log.info("build.time: {}", properties.get("git.build.time"));
        log.info("build.user: {}", properties.get("git.build.user.name"));
        log.info("-------------------------------------");

        log.info("Adding shutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                doServicesCleanup();
            }
        });

        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");

        // TODO(mtoader): Redirected where?
        options.addOption("redirectStdOut", true,
                          "will cause the stdout to be redirected");
        options.addOption("redirectStdErr", true,
                          "will cause the stderr to be redirected");

        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);

        redirectStdOutAndErrIfRequested(cl);

        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");
        if (!java.nio.file.Files.isReadable(Paths.get(configFilePath))) {
            // The config file is missing. Exits Midolman.
            Midolman.exitsMissingConfigFile(configFilePath);
        }

        injector = Guice.createInjector(
            new ZookeeperConnectionModule(),
            new VersionModule(),
            new HostModule(),
            new ConfigProviderModule(configFilePath),
            new DatapathModule(),
            new ClusterClientModule(),
            new SerializationModule(),
            new MidolmanActorsModule(),
            new ResourceProtectionModule(),
            new MidolmanModule(),
            new InterfaceScannerModule()
        );

        // start the services
        injector.getInstance(MidostoreSetupService.class).startAndWait();
        injector.getInstance(MidolmanService.class).startAndWait();

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();

        log.info("{} was initialized", MidolmanActorsService.class);

        log.info("main finish");
        serviceEvent.start();
    }

    private void doServicesCleanup() {
        log.info("SHUTTING DOWN");

        if (injector == null)
            return;

        MidolmanService instance =
            injector.getInstance(MidolmanService.class);

        if (instance.state() == Service.State.TERMINATED)
            return;

        try {
            instance.stopAndWait();
        } catch (Exception e) {
            log.error("Exception ", e);
        } finally {
            log.info("Exiting. BYE (signal)!");
            serviceEvent.exit();
        }
    }

    private void redirectStdOutAndErrIfRequested(CommandLine commandLine) {

        String targetStdOut = commandLine.getOptionValue("redirectStdOut");
        if (targetStdOut != null) {
            try {
                File targetStdOutFile = new File(targetStdOut);
                if (targetStdOutFile.isFile() && targetStdOutFile.canWrite()) {
                    PrintStream newStdOutStr = new PrintStream(
                        targetStdOutFile);
                    newStdOutStr.println("[Begin redirected output]");

                    System.setOut(newStdOutStr);
                }
            } catch (FileNotFoundException e) {
                log.error("Could not redirect stdout to {}", targetStdOut, e);
            }
        }

        String targetStdErr = commandLine.getOptionValue("redirectStdErr");

        if (targetStdErr != null) {
            try {
                File targetStdErrFile = new File(targetStdErr);
                if (targetStdErrFile.isFile() && targetStdErrFile.canWrite()) {
                    PrintStream newStdErrStr = new PrintStream(
                        targetStdErrFile);
                    newStdErrStr.println("[Begin redirected output]");

                    System.setErr(newStdErrStr);
                }
            } catch (FileNotFoundException e) {
                log.error("Could not redirect stderr to {}", targetStdErr, e);
            }
        }
    }

    /**
     * Expose Midolman instance and Guice injector
     * Using the following methods makes it easier for host management
     * tools (e.g. dashboard implemented in servlet) to access Midolman's
     * internal data structure directly and diagnose issues at runtime.
     */
    private static class MidolmanHolder {
        private static final Midolman instance = new Midolman();
    }

    public static Midolman getInstance() {
        return MidolmanHolder.instance;
    }

    public static Injector getInjector() {
        return getInstance().injector;
    }

    public static void main(String[] args) {

        try {
            Midolman midolman = getInstance();
            midolman.run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            serviceEvent.exit();
            System.exit(-1);
        }
    }
}
