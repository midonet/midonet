/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman;

import com.google.common.base.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.midonet.midolman.guice.FlowStateCacheModule;
import org.midonet.midolman.guice.InterfaceScannerModule;
import org.midonet.midolman.guice.MidolmanActorsModule;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.MonitoringStoreModule;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.datapath.DatapathModule;
import org.midonet.midolman.guice.reactor.ReactorModule;
import org.midonet.midolman.guice.state.ConditionSetModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.host.guice.HostModule;
import org.midonet.midolman.monitoring.MonitoringAgent;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.remote.RemoteHost;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.Properties;

public class Midolman {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);

    private Injector injector;

    private MonitoringAgent monitoringAgent;

    private Midolman() {
    }

    private void run(String[] args) throws Exception {
        // log git commit info
        Properties properties = new Properties();
        properties.load(
            getClass().getClassLoader().getResourceAsStream("git.properties"));
        log.info("main start -------------------------");
        log.info("branch: {}", properties.get("git.branch"));
        log.info("commit.time: {}", properties.get("git.commit.time"));
        log.info("commit.id: {}", properties.get("git.commit.id"));
        log.info("commit.user: {}", properties.get("git.commit.user.name"));
        log.info("build.time: {}", properties.get("git.build.time"));
        log.info("build.user: {}", properties.get("git.build.user.name"));
        log.info("-------------------------------------");

        log.info("Added SIGTERM handling for cleanup");
        Signal.handle(new Signal("TERM"), new SignalHandler() {
            @Override
            public void handle(Signal sig) {
                doServicesCleanup();
            }
        });

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

        injector = Guice.createInjector(
            new ZookeeperConnectionModule(),
            new ReactorModule(),
            new VersionModule(),
            new HostModule(),
            new ConfigProviderModule(configFilePath),
            new DatapathModule(),
            new MonitoringStoreModule(),
            new ClusterClientModule(),
            new SerializationModule(),
            new FlowStateCacheModule(),
            new MidolmanActorsModule(),
            new MidolmanModule(),
            new InterfaceScannerModule(),
            new ConditionSetModule()
        );

        // start the services
        injector.getInstance(MidostoreSetupService.class).startAndWait();
        injector.getInstance(MidolmanService.class).startAndWait();

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();
        monitoringAgent = injector.getInstance(MonitoringAgent.class);
        monitoringAgent.startMonitoringIfEnabled();

        log.info("{} was initialized", MidolmanActorsService.class);

        log.info("main finish");
    }

    private void doServicesCleanup() {
        if ( injector == null )
            return;

        monitoringAgent.stop();

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

    public static void main(String[] args) {
        try {
            RemoteHost.getSpecification();

            new Midolman().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }
}
