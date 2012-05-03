/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.Map;

import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Metered;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricProcessor;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.reporting.AbstractPollingReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.store.Store;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/24/12
 */
public class MidoReporter extends AbstractPollingReporter implements MetricProcessor<String> {

    private final static Logger log = LoggerFactory.getLogger(MidoReporter.class);

    private Store store;

    public MidoReporter(Store cassandraStore, MetricsRegistry registry,
                        String name) {
        super(registry, name);
        store = cassandraStore;
    }

    @Override
    public void run() {
        try {
            for (Map.Entry<MetricName, Metric> entry : getMetricsRegistry().allMetrics().entrySet()) {
                entry.getValue().processWith(this, entry.getKey(),"");
            }
        } catch (Exception e) {
            log.error("Error in collecting ZK stats", e);
        }
    }

    @Override
    public void processGauge(MetricName name, Gauge<?> gauge, String s) {
        String val = gauge.value().toString();
        //TODO what shall we do with granularity? I'd map it in scope but probably for first version
        // it's useless
        store.addTSPoint(name.getGroup(), System.currentTimeMillis(), val, name.getName(), name.getScope());
    }

    @Override
    public void processMeter(MetricName metricName, Metered metered, String s)
            throws Exception {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void processCounter(MetricName metricName, Counter counter, String s)
            throws Exception {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void processHistogram(MetricName metricName, Histogram histogram,
                                 String s) throws Exception {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void processTimer(MetricName metricName, Timer timer, String s)
            throws Exception {
        //To change body of implemented methods use File | Settings | File Templates.
    }

}
