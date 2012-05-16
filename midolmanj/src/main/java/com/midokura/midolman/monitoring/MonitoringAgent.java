/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/25/12
 */
public class MonitoringAgent {
    private final static Logger log =
            LoggerFactory.getLogger(MonitoringAgent.class);

    public static final String ZKJMXPATH = "ZkJMX";

    private String zkJMXServerPath;

    @Inject
    private Store store;

    @Inject
    public MonitoringAgent(@Named(ZKJMXPATH) String zkJMXPAth) {
        this.zkJMXServerPath = zkJMXPAth;
    }


    public void startMonitoring(long pollingTime, TimeUnit timeUnit) {
        // With no store we won't be able to write the monitoring data
        if (store == null)
            return;
        MidoReporter reporter = new MidoReporter(store,
                                                 "MidonetMonitoring");
        VMMetricsCollection.addMetricsToRegistry();

        ZookeeperMetricsCollection.addMetricsToRegistry(zkJMXServerPath);
        reporter.start(pollingTime, timeUnit);
    }
}
