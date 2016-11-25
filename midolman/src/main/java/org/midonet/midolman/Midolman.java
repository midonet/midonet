/*
 * Copyright 2016 Midokura SARL
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

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Promise;
import scala.concurrent.Promise$;

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
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
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.backend.zookeeper.ZookeeperConnectionWatcher;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendModule;
import org.midonet.conf.HostIdGenerator;
import org.midonet.conf.LoggerLevelWatcher;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.logging.FlowTracingAppender;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.simulation.PacketContext$;
import org.midonet.util.cLibrary;
import org.midonet.util.process.MonitoredDaemonProcess;

public class Midolman {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);

    public static final int MIDOLMAN_ERROR_CODE_MISSING_CONFIG_FILE = 1;
    public static final int MIDOLMAN_ERROR_CODE_LOST_HOST_OWNERSHIP = 2;
    public static final int MIDOLMAN_ERROR_CODE_UNHANDLED_EXCEPTION = 6001;
    public static final int MIDOLMAN_ERROR_CODE_PACKET_WORKER_DIED = 6002;
    public static final int MIDOLMAN_ERROR_CODE_MINION_PROCESS_DIED = 6003;
    public static final int MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED = 6004;

    private static final long MIDOLMAN_EXIT_TIMEOUT_MILLIS = 30000;
    private static final int MINION_PROCESS_MAXIMUM_STARTS = 3;
    private static final int MINION_PROCESS_FAILING_PERIOD = 180000;
    private static final int MINION_PROCESS_WAIT_TIMEOUT = 10;

    public static final String VERSION =
        Midolman.class.getPackage().getImplementationVersion();
    public static final String VENDOR =
        Midolman.class.getPackage().getImplementationVendor();

    private Injector injector;

    private WatchedProcess watchedProcess = new WatchedProcess();

    private volatile MonitoredDaemonProcess minionProcess = null;

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
    private static void exitsMissingConfigFile(String configFilePath) {
        log.error("MidoNet Agent config file \'" + configFilePath + "\' "
                  + "missing: exiting");
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

    private void initialize(String[] args) throws IOException {
        log.info("MidoNet Agent {} starting...", VERSION);
        log.info("-------------------------------------");

        // Log version, vendor and GIT commit information.
        log.info("Version: {}", VERSION);
        log.info("Vendor: {}", VENDOR);
        InputStream gitStream =
            getClass().getClassLoader().getResourceAsStream("git.properties");
        if (gitStream != null) {
            Properties properties = new Properties();
            properties.load(gitStream);
            log.info("Branch: {}", properties.get("git.branch"));
            log.info("Commit time: {}", properties.get("git.commit.time"));
            log.info("Commit id: {}", properties.get("git.commit.id"));
            log.info("Commit user: {}", properties.get("git.commit.user.name"));
            log.info("Build time: {}", properties.get("git.build.time"));
            log.info("Build user: {}", properties.get("git.build.user.name"));
        }
        log.info("-------------------------------------");

        // log cmdline and JVM info
        log.info("Command-line arguments: {}", Arrays.toString(args));
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMxBean.getInputArguments();
        log.info("JVM options: ");
        for(String a: arguments){
            log.info("  {}", a);
        }
        log.info("-------------------------------------");

        log.info("Adding shutdown hook");
        Runtime.getRuntime().addShutdownHook(new Thread("shutdown") {
            @Override
            public void run() {
                doServicesCleanup();
            }
        });
    }

    private void setUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                log.error("Unhandled exception: ", e);
                dumpStacks();
                System.exit(MIDOLMAN_ERROR_CODE_UNHANDLED_EXCEPTION);
            }
        });
    }

    private void run(String[] args) throws Exception {
        Promise<Boolean> initializationPromise = Promise$.MODULE$.apply();
        setUncaughtExceptionHandler();
        int initTimeout = Integer.valueOf(
            System.getProperty("midolman.init_timeout", "120"));
        try {
            watchedProcess.start(initializationPromise, initTimeout);
            initialize(args);
        } catch (Throwable t) {
            log.error("Exception while initializing the MidoNet Agent", t);
            watchedProcess.close();
            throw t;
        }

        minionProcess = new MonitoredDaemonProcess(
            "/usr/share/midolman/minions-start", log, "org.midonet.services",
            MINION_PROCESS_MAXIMUM_STARTS, MINION_PROCESS_FAILING_PERIOD,
            (Exception e) -> {
                log.debug(e.getMessage());
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_MINION_PROCESS_DIED);
            });
        minionProcess.startAsync();

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

        configurator.observableRuntimeConfig(HostIdGenerator.getHostId()).
            subscribe(new LoggerLevelWatcher(scala.Option.apply("agent")));

        MidolmanConfig config = createConfig(configurator);
        MetricRegistry metricRegistry = new MetricRegistry();

        Reflections reflections = new Reflections("org.midonet");
        injector = Guice.createInjector(
            new MidonetBackendModule(config.zookeeper(),
                                     scala.Option.apply(reflections),
                                     metricRegistry),
            new ZookeeperConnectionModule(ZookeeperConnectionWatcher.class),
            new SerializationModule(),
            new LegacyClusterModule()
        );

        injector = injector.createChildInjector(
            new MidolmanModule(injector, config, metricRegistry, reflections));

        ConfigRenderOptions renderOpts = ConfigRenderOptions.defaults()
            .setJson(true)
            .setComments(false)
            .setOriginComments(false)
            .setFormatted(true);
        Config conf = injector.getInstance(MidolmanConfig.class).conf();
        Boolean showConfigPasswords =
            System.getProperties().containsKey("midonet.show_config_passwords");
        log.info("Loaded configuration: {}",
                 configurator.dropSchema(conf, showConfigPasswords).root()
                             .render(renderOpts));

        // start the services
        injector.getInstance(MidonetBackend.class)
            .startAsync()
            .awaitRunning();
        injector.getInstance(MidolmanService.class)
            .startAsync()
            .awaitRunning();

        enableFlowTracingAppender(
                injector.getInstance(FlowTracingAppender.class));

        log.info("Running manual GC to tenure pre-allocated objects");
        System.gc();

        if (config.lockMemory())
            lockMemory();

        if (!initializationPromise.trySuccess(true)) {
            log.error("MidoNet Agent failed to initialize in {} seconds and " +
                      "is shutting down: try increasing the initialization " +
                      "timeout using the \"midonet.init_timeout\" system " +
                      "property or check the upstart logs to determine the " +
                      "cause of the failure.", initTimeout);
            System.exit(-1);
        }
        log.info("MidoNet Agent started");

        injector.getInstance(MidolmanService.class).awaitTerminated();
    }

    private void doServicesCleanup() {
        log.info("MidoNet Agent shutting down...");

        try {
            minionProcess.stopAsync()
                .awaitTerminated(MINION_PROCESS_WAIT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Minion process failed while stopping.", e);
        }

        if (injector == null)
            return;

        MidolmanService instance =
            injector.getInstance(MidolmanService.class);

        if (instance.state() == Service.State.TERMINATED)
            return;

        try {
            instance.stopAsync().awaitTerminated();
        } catch (Exception e) {
            log.error("Agent service failed while stopping", e);
        } finally {
            watchedProcess.close();
            log.info("MidoNet Agent exiting. Bye!");
        }
    }

    @VisibleForTesting
    public static void enableFlowTracingAppender(FlowTracingAppender appender) {
        Object logger = PacketContext$.MODULE$.traceLog().underlying();
        Object loggerFactory = LoggerFactory.getILoggerFactory();
        if (logger instanceof ch.qos.logback.classic.Logger
            && loggerFactory instanceof ch.qos.logback.classic.LoggerContext) {
            ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger)logger;
            ch.qos.logback.classic.LoggerContext loggerCtx =
                (ch.qos.logback.classic.LoggerContext)loggerFactory;
            appender.setContext(loggerCtx);
            appender.start();

            logbackLogger.addAppender(appender);
            logbackLogger.setAdditive(true);
        } else {
            log.warn("Unable to get logback logger, FlowTracingAppender"
                    + " not enabled. Logger is of type {},"
                    +" LoggerFactory is of type {}",
                    logger.getClass(), loggerFactory.getClass());
        }
    }

    private static MidolmanConfig createConfig(MidoNodeConfigurator configurator) {
        try {
            return new MidolmanConfig(
                configurator.runtimeConfig(HostIdGenerator.getHostId()),
                configurator.mergedSchemas());
        } catch (HostIdGenerator.PropertiesFileNotWritableException e) {
            throw new RuntimeException(e);
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
            log.error("Unhandled exception in main method", e);
            dumpStacks();
            System.exit(-1);
        }
    }

    public static void exitAsync(final int status) {
        new Thread("exit") {
            @Override
            public void run() {
                Runtime.getRuntime().exit(status);
            }
        }.start();
        new Timer("exit-timer", true).schedule(new TimerTask() {
            @Override
            public void run() {
                Runtime.getRuntime().halt(status);
            }
        }, MIDOLMAN_EXIT_TIMEOUT_MILLIS);
    }
}
