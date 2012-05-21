/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import static java.lang.String.format;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.monitoring.vrn.VRNMonitoringObserver;
import com.midokura.midolman.vrn.VRNControllerObserver;

/**
 * This is the main entry point to the monitoring functionality. It's called
 * by {@link com.midokura.midolman.Midolman#run(String[])} if it was enabled in
 * the configuration file.
 */
public class MonitoringAgent {
    private final static Logger log =
        LoggerFactory.getLogger(MonitoringAgent.class);

    @Nullable
    @Inject
    private Store store;

    @Inject
    MonitoringConfiguration configuration;

    @Inject
    HostIdProvider hostIdProvider;

    @Inject
    VMMetricsCollection vmMetrics;

    @Inject
    ZookeeperMetricsCollection zookeeperMetrics;

    MidoReporter reporter;

    public void startMonitoring() {

        vmMetrics.registerMetrics();

        int zkJmxPort = configuration.getMonitoringZookeeperJMXPort();

        if (zkJmxPort != -1) {
            String zkJmxUrl =
                format("service:jmx:rmi:///jndi/rmi://localhost:%d/jmxrmi",
                       zkJmxPort);

            zookeeperMetrics.registerMetrics(zkJmxUrl);
        }

        if (store != null) {
            reporter = new MidoReporter(store, "MidonetMonitoring");
            reporter.start(
                configuration.getMonitoringCassandraReporterPoolTime(),
                TimeUnit.MILLISECONDS);
        } else {
            log.warn("The metrics publisher to Cassandra store didn't start " +
                         "because the store was not initialized. Most likely " +
                         "the connection to cassandra failed.");
        }
    }

    public void stop() {
        log.info("Monitoring agent is shutting down");
        if (reporter != null)
            reporter.shutdown();
    }

    public static MonitoringAgent bootstrapMonitoring(
        ConfigProvider configProvider, HostIdProvider hostIdProvider) {
        Injector injector = Guice.createInjector(
            new MonitoringModule(configProvider, hostIdProvider));

        MonitoringAgent monitoringAgent =
            injector.getInstance(MonitoringAgent.class);

        monitoringAgent.startMonitoring();

        return monitoringAgent;
    }

    public VRNControllerObserver createVRNObserver() {
        return new VRNMonitoringObserver();
    }
}
