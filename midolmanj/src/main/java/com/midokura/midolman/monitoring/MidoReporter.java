/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Metered;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricProcessor;
import com.yammer.metrics.core.MetricsRegistryListener;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.reporting.AbstractPollingReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.metrics.MetricNameWrapper;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/24/12
 */
public class MidoReporter extends AbstractPollingReporter
        implements MetricProcessor<String>, MetricsRegistryListener {

    private final static Logger log = LoggerFactory.getLogger(
            MidoReporter.class);

    private static List<MetricName> metricsToStore = new ArrayList<MetricName>();

    private Store store;

    public MidoReporter(Store cassandraStore, String name) {
        // TODO(rossella) MetricsRegistry allow a maximum of 1024 items
        super(Metrics.defaultRegistry(), name);
        store = cassandraStore;
        Metrics.defaultRegistry().addListener(this);
    }

    @Override
    public void run() {
        for (Map.Entry<MetricName, Metric> entry : getMetricsRegistry().allMetrics()
                .entrySet()) {
            try {
                entry.getValue().processWith(this, entry.getKey(), "");
            } catch (Exception e) {
                log.error("Error in sampling metric {}",
                          new Object[]{entry.getKey().getName()}, e);
            }
        }
        // save the info of the last added metrics into the store
        addNewMetricsInfoToStore();
    }

    private void addNewMetricsInfoToStore() {
        for (MetricName metric : metricsToStore) {
            MetricNameWrapper metricNameWrapper = new MetricNameWrapper(metric);
            store.addMetric(metricNameWrapper.getTargetIdentifier(),
                            metricNameWrapper.getName());
            log.debug("Added metric {} for target {}",
                      new Object[]{metricNameWrapper.getName(), metricNameWrapper
                              .getTargetIdentifier()});
            store.addGranularity(metricNameWrapper.getTargetIdentifier(),
                                 metricNameWrapper.getName(),
                                 metricNameWrapper.getGranularity());
            log.debug("Added granularity {} for metric {}",
                      new Object[]{metricNameWrapper.getGranularity(), metricNameWrapper
                              .getName()});
        }
        // clear list
        metricsToStore.clear();
    }

    @Override
    public void processGauge(MetricName name, Gauge<?> gauge, String s)
            throws Exception {

        Long val = Long.decode(gauge.value().toString());
        MetricNameWrapper metricWrapper = new MetricNameWrapper(name);

        //TODO what shall we do with granularity? Probably for first version it's useless
        store.addTSPoint(metricWrapper.getTargetIdentifier(),
                         System.currentTimeMillis(), val,
                         metricWrapper.getName());
    }

    @Override
    public void processMeter(MetricName metricName, Metered metered, String s)
            throws Exception {
        Long val = metered.count();
        MetricNameWrapper metricWrapper = new MetricNameWrapper(metricName);

        store.addTSPoint(metricWrapper.getTargetIdentifier(),
                         System.currentTimeMillis(), val,
                         metricWrapper.getName());
    }

    @Override
    public void processCounter(MetricName metricName, Counter counter, String s)
            throws Exception {

        Long val = counter.count();
        MetricNameWrapper metricWrapper = new MetricNameWrapper(metricName);

        store.addTSPoint(metricWrapper.getTargetIdentifier(),
                         System.currentTimeMillis(), val,
                         metricWrapper.getName());
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

    @Override
    public void onMetricAdded(MetricName name, Metric metric) {

        metricsToStore.add(name);
    }

    @Override
    public void onMetricRemoved(MetricName name) {
    }
}
