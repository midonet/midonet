/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigRenderOptions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.midolman.config.MidolmanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.services.LegacyStorageService;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendModule;
import org.midonet.cluster.storage.StateStorageModule;
import org.midonet.event.agent.ServiceEvent;
import org.midonet.midolman.cluster.InterfaceScannerModule;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.MidolmanActorsModule;
import org.midonet.midolman.cluster.MidolmanModule;
import org.midonet.midolman.cluster.ResourceProtectionModule;
import org.midonet.midolman.cluster.datapath.DatapathModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.state.FlowStateStorageModule;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.host.guice.HostModule;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

public class Midolman {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);
    static private final ServiceEvent serviceEvent = new ServiceEvent();

    static final int MIDOLMAN_ERROR_CODE_MISSING_CONFIG_FILE = 1;

    private Injector injector;

    WatchedProcess watchedProcess = new WatchedProcess();

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
        watchedProcess.start();
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

        // log cmdline and JVM info
        log.info("cmdline args: {}", Arrays.toString(args));
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMxBean.getInputArguments();
        log.info("JVM options: ");
        for(String a: arguments){
            log.info("  {}", a);
        }
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

        MidoNodeConfigurator configurator =
            MidoNodeConfigurator.forAgents(configFilePath);
        if (configurator.deployBundledConfig())
            log.info("Deployed new configuration schema into NSDB");


        injector = Guice.createInjector(
            MidonetBackendModule.apply(),
            new ZookeeperConnectionModule(ZookeeperConnectionWatcher.class),
            new SerializationModule(),
            new HostModule(),
            new MidolmanConfigModule(configurator),
            new StateStorageModule(),
            new DatapathModule(),
            new LegacyClusterModule(),
            new MidolmanActorsModule(),
            new ResourceProtectionModule(),
            new MidolmanModule(),
            new FlowStateStorageModule(),
            new InterfaceScannerModule()
        );

        // start the services
        injector.getInstance(LegacyStorageService.class)
            .startAsync()
            .awaitRunning();
        injector.getInstance(MidonetBackend.class)
            .startAsync()
            .awaitRunning();
        injector.getInstance(MidolmanService.class)
            .startAsync()
            .awaitRunning();

        ConfigRenderOptions renderOpts = ConfigRenderOptions.defaults().
                    setComments(true).
                    setOriginComments(true).
                    setFormatted(true);
        Config conf = injector.getInstance(MidolmanConfig.class).conf();
        log.info("Loaded configuration: {}", conf.root().render(renderOpts));

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();

        log.info("{} was initialized", MidolmanActorsService.class);

        log.info("Running manual GC to tenure preallocated objects");
        injector.getInstance(MidolmanActorsService.class)
            .awaitFlowControllerRunning(30, TimeUnit.SECONDS);
        System.gc();

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
            instance.stopAsync().awaitTerminated();
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
     * tools to access Midolman's internal data structure directly and
     * diagnose issues at runtime.
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
