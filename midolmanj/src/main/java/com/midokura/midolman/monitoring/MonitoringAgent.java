/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import static java.lang.String.format;

import akka.actor.ActorRef;
import com.google.inject.Inject;
import com.midokura.midolman.monitoring.MonitoringActor;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.services.MidolmanActorsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.store.Store;

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
    MidolmanConfig config;

    @Inject
    MonitoringConfiguration configuration;

    @Inject
    VMMetricsCollection vmMetrics;

    @Inject
    ZookeeperMetricsCollection zookeeperMetrics;

    @Inject
    MidolmanActorsService midolmanActorsService = null;

    MidoReporter reporter;

    public void startMonitoringIfEnabled() {

       if (config.getMidolmanEnableMonitoring()) {

        vmMetrics.registerMetrics();

        int zkJmxPort = configuration.getMonitoringZookeeperJMXPort();

        if (zkJmxPort != -1) {
            String zkJmxUrl =
                format("service:jmx:rmi:///jndi/rmi://localhost:%d/jmxrmi",
                       zkJmxPort);

            zookeeperMetrics.registerMetrics(zkJmxUrl);
        }

        if (store != null) {
            store.initialize();
            reporter = new MidoReporter(store, "MidonetMonitoring");
            reporter.start(
                configuration.getMonitoringCassandraReporterPullTime(),
                TimeUnit.MILLISECONDS);
        } else {
            log.warn("The metrics publisher to the data store didn't start " +
                         "because the store was not initialized. Most likely " +
                         "the connection to the store failed.");
        }


       }
    }

    public void stop() {
        if (reporter != null) {
            log.info("Monitoring agent is shutting down");
            reporter.shutdown();
        }

    }

}
