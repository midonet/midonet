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

import java.io.File;
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

import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.midolman.datapath.FlowExpirator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Scheduler;

import org.midonet.cluster.backend.zookeeper.ZookeeperConnectionWatcher;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendModule;
import org.midonet.conf.HostIdGenerator;
import org.midonet.conf.LoggerLevelWatcher;
import org.midonet.conf.LoggerMetricsWatcher;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.jna.CLibrary;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.host.services.HostService;
import org.midonet.midolman.logging.FlowTracingAppender;
import org.midonet.midolman.management.JmxConnectorServer;
import org.midonet.midolman.management.SimpleHTTPServerService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.simulation.PacketContext$;
import org.midonet.midolman.topology.VirtualToPhysicalMapper;
import org.midonet.midolman.topology.VirtualTopology;
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

    private static final String FAST_REBOOT_FILE =
        "/var/run/midolman/fast_reboot.file";

    private Injector injector;

    private WatchedProcess watchedProcess = new WatchedProcess();

    private volatile MonitoredDaemonProcess minionProcess = null;

    private static Boolean fastRebootBackup = false;

    private Midolman() {
    }

    private static void lockMemory() {
        try {
            CLibrary.mlockall(CLibrary.MCL_FUTURE | CLibrary.MCL_CURRENT);
            log.info("Successfully locked the process address space to RAM.");
        } catch (LastErrorException e) {
            log.warn("Failed to lock process into RAM: {}",
                     CLibrary.strerror(e.getErrorCode()));
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
                boolean fastReboot = !fastRebootBackup && isFastReboot();
                log.debug("Fast reboot - isFastReboot? {}", fastReboot);
                if (fastReboot) {
                    doServicesCleanupOnFastReboot();
                } else {
                    doServicesCleanup();
                }
            }
        });
    }

    private boolean isFastReboot() {
        return new File(FAST_REBOOT_FILE).exists();
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
        fastRebootBackup = isFastReboot();

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

        MidolmanConfig config = createConfig(configurator);

        MetricRegistry metricRegistry = new MetricRegistry();

        Observable<Config> configObservable = configurator
            .observableRuntimeConfig(HostIdGenerator.getHostId(), false);

        configObservable
            .subscribe(new LoggerLevelWatcher(scala.Option.apply("agent")));

        configObservable
            .subscribe(new LoggerMetricsWatcher("agent", metricRegistry));

        ConfigRenderOptions renderOpts = ConfigRenderOptions.defaults()
            .setJson(true)
            .setComments(false)
            .setOriginComments(false)
            .setFormatted(true);
        Config conf = config.conf();
        Boolean showConfigPasswords =
            System.getProperties().containsKey("midonet.show_config_passwords");
        log.info("Loaded configuration: {}",
                 configurator.dropSchema(conf, showConfigPasswords).root()
                     .render(renderOpts));

        minionProcess = new MonitoredDaemonProcess(
            "/usr/share/midolman/minions-start", log, "org.midonet.services",
            MINION_PROCESS_MAXIMUM_STARTS, MINION_PROCESS_FAILING_PERIOD,
            (Exception e) -> {
                log.debug(e.getMessage());
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_MINION_PROCESS_DIED);
            });
        minionProcess.startAsync();

        if (config.lockMemory())
            lockMemory();

        FlowTablePreallocation preallocation;
        if (config.offHeapTables()) {
            preallocation = new FlowTableNoPreallocation();
        } else {
            preallocation = new FlowTablePreallocationImpl(config);
            preallocation.allocateAndTenure();
        }

        // MidonetBackendModule uses reflections to define additional
        // zoom storage classes, that are not needed for the agent, so
        // we can safely pass a 'None' option
        MidonetBackendModule midonetBackendModule = new MidonetBackendModule(
            config.zookeeper(), scala.Option.apply(null), metricRegistry,
            false);

        ZookeeperConnectionModule zkConnectionModule =
            new ZookeeperConnectionModule(ZookeeperConnectionWatcher.class);

        MidolmanModule midolmanModule = new MidolmanModule(
            config,
            midonetBackendModule.storeBackend(),
            midonetBackendModule.conf(),
            zkConnectionModule.getDirectoryReactor(),
            metricRegistry,
            preallocation);

        injector = Guice.createInjector(
            midonetBackendModule, zkConnectionModule, midolmanModule
        );

        // start the services
        MidonetBackend backend = injector.getInstance(MidonetBackend.class);
        backend.startAsync().awaitRunning();

        RebootBarriers barriers = null;

        if (fastRebootBackup) {
            log.info("Fast reboot (backup): initialization finished.");
            barriers = new RebootBarriers(false);
            barriers.waitOnBarrier(1, 10, TimeUnit.SECONDS);
            barriers.waitOnBarrier(2, 5, TimeUnit.SECONDS);
        }

        injector.getInstance(MidolmanService.class)
            .startAsync()
            .awaitRunning();

        if (fastRebootBackup) {
            log.info("Fast reboot (backup): services started.");
            barriers.waitOnBarrier(3, 15, TimeUnit.SECONDS);
            new File(FAST_REBOOT_FILE).delete();
            fastRebootBackup = false;
        }

        enableFlowTracingAppender(
                injector.getInstance(FlowTracingAppender.class));

        if (!initializationPromise.trySuccess(true)) {
            log.error("MidoNet Agent failed to initialize in {} seconds and " +
                      "is shutting down: try increasing the initialization " +
                      "timeout using the \"midonet.init_timeout\" system " +
                      "property or check the upstart logs to determine the " +
                      "cause of the failure.", initTimeout);
            System.exit(-1);
        }
        log.info("MidoNet Agent started");

        if (config.reclaimDatapath()) {
            FlowExpirator expirator = injector.getInstance(FlowExpirator.class);
            new Thread(expirator::expireAllFlows, "flow-expirator").start();
        }

        injector.getInstance(MidolmanService.class).awaitTerminated();
    }

    private void doServicesCleanup() {
        log.info("MidoNet Agent shutting down...");

        try {
            if (minionProcess != null)
                minionProcess.stopAsync()
                    .awaitTerminated(MINION_PROCESS_WAIT_TIMEOUT,
                                     TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Minion process failed while stopping.", e);
        }

        log.info("Minion process stopped.");

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

    private void doServicesCleanupOnFastReboot() {
        log.info("Fast reboot (main) Midonet Agent coordinated shutdown started...");

        MidonetBackend backend = injector
            .getInstance(MidonetBackend.class);
        MidonetBackendConfig backendConfig = injector
            .getInstance(MidonetBackendConfig.class);
        RebootBarriers barriers = new RebootBarriers(true);
        MidolmanActorsService actorsService = injector
            .getInstance(MidolmanActorsService.class);

        try {
            minionProcess.stopAsync()
                .awaitTerminated(MINION_PROCESS_WAIT_TIMEOUT, TimeUnit.SECONDS);
            minionProcess = null;
        } catch (Exception e) {
            log.error("Minion process failed while stopping.", e);
        }

        log.info("Minion process stopped.");

        log.info("Fast reboot (main): minion stopped");
        barriers.waitOnBarrier(1, 10, TimeUnit.SECONDS);

        // Stop non-critical services that have hard coded resources
        // that could interfere with a concurrent MidoNet Agent instance.
        actorsService.stopRoutingHandlerActors();
        actorsService.stopMetadataActor();

        Service[] services = {
            injector.getInstance(HostService.class),
            injector.getInstance(SimpleHTTPServerService.class),
            injector.getInstance(JmxConnectorServer.class),
            injector.getInstance(VirtualToPhysicalMapper.class),
        };

        for (Service service : services) {
            log.info("Stopping service: {}", service);
            service.stopAsync().awaitTerminated();
            log.info("Service {} stopped.", service);
        }

        log.info("Fast reboot (main): non-critical services stopped.");
        barriers.waitOnBarrier(2, 5, TimeUnit.SECONDS);
        barriers.waitOnBarrier(3, 15, TimeUnit.SECONDS);

        doServicesCleanup();
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
                configurator.runtimeConfig(HostIdGenerator.getHostId(), false),
                configurator.mergedSchemas(),
                true);
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
