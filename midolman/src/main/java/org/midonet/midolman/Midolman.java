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

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.common.util.concurrent.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.sun.jna.LastErrorException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigRenderOptions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.midonet.conf.HostIdGenerator;
import org.midonet.conf.LoggerLevelWatcher;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.midolman.config.MidolmanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.services.LegacyStorageService;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendModule;
import org.midonet.cluster.storage.StateStorageModule;
import org.midonet.event.agent.ServiceEvent;
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
import org.midonet.util.cLibrary;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;

public class Midolman {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);
    static private final ServiceEvent serviceEvent = new ServiceEvent();

    public static final int MIDOLMAN_ERROR_CODE_MISSING_CONFIG_FILE = 1;
    public static final int MIDOLMAN_ERROR_CODE_LOST_HOST_OWNERSHIP = 2;

    private Injector injector;

    WatchedProcess watchedProcess = new WatchedProcess();

    private Midolman() {
    }

    private static void lockMemory() {
        try {
            cLibrary.lib.mlockall(cLibrary.MCL_FUTURE | cLibrary.MCL_CURRENT);
            log.info("Successfully locked the process address space to RAM.");
        } catch (LastErrorException e) {
            log.warn("Failed to lock process into RAM: {}",
                cLibrary.lib.strerror(e.getErrorCode()));
            log.warn("This implies that parts of the agents may be swapped out "+
                    "causing long GC pauses that have various adverse effects. "+
                    "It's strongly recommended that this process runs either as "+
                    "root or with the CAP_IPC_LOCK capability and a high enough "+
                    "memlock limit (RLIMIT_MEMLOCK).");
            log.warn("You may disable these warnings by setting the "+
                    "'agent.midolman.lock_memory' configuration key to 'false' "+
                    "in mn-conf(1).");
        }
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

    public static void dumpStacks() {
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        for (Thread thread: traces.keySet()) {
            System.err.print("\"" + thread.getName() + "\" ");
            if (thread.isDaemon())
                System.err.print("daemon ");
            System.err.print(String.format("prio=%x tid=%x %s [%x]\n",
                thread.getPriority(), thread.getId(),
                thread.getState(), System.identityHashCode(thread)));

            StackTraceElement[] trace = traces.get(thread);
            for (StackTraceElement e: trace) {
                System.err.println("        at " + e.toString());
            }
        }
    }

    private void setUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                log.error("Uncaught exception: ", e);
                dumpStacks();
                System.exit(-1);
            }
        });
    }

    private void run(String[] args) throws Exception {
        Promise<Boolean> initializationPromise = Promise$.MODULE$.apply();
        setUncaughtExceptionHandler();
        watchedProcess.start(initializationPromise);

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

        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);

        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");
        if (!java.nio.file.Files.isReadable(Paths.get(configFilePath))) {
            // The config file is missing. Exits Midolman.
            Midolman.exitsMissingConfigFile(configFilePath);
        }

        MidoNodeConfigurator configurator =
            MidoNodeConfigurator.apply(configFilePath);
        if (configurator.deployBundledConfig())
            log.info("Deployed new configuration schema into NSDB");

        MidolmanConfig config = MidolmanConfigModule.createConfig(configurator);

        injector = Guice.createInjector(
            new MidolmanConfigModule(config),
            new MidonetBackendModule(config.zookeeper()),
            new ZookeeperConnectionModule(ZookeeperConnectionWatcher.class),
            new SerializationModule(),
            new HostModule(),
            new StateStorageModule(),
            new DatapathModule(),
            new LegacyClusterModule(),
            new MidolmanActorsModule(),
            new ResourceProtectionModule(),
            new MidolmanModule(),
            new FlowStateStorageModule()
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
                    setComments(false).
                    setOriginComments(false).
                    setFormatted(true);
        Config conf = injector.getInstance(MidolmanConfig.class).conf();
        log.info("Loaded configuration: {}", conf.root().render(renderOpts));

        configurator.observableRuntimeConfig(HostIdGenerator.getHostId()).
                subscribe(new LoggerLevelWatcher(scala.Option.apply("agent")));

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();
        log.info("Actors service was initialized");

        log.info("Running manual GC to tenure preallocated objects");
        System.gc();

        if (config.lockMemory())
            lockMemory();

        initializationPromise.success(true);
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
        } catch (Throwable e) {
            log.error("main caught", e);
            dumpStacks();
            serviceEvent.exit();
            System.exit(-1);
        }
    }
}
