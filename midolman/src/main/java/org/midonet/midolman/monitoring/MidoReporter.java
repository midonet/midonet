/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package org.midonet.midolman.monitoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.inject.Inject;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;
import com.yammer.metrics.reporting.AbstractPollingReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.monitoring.store.Store;

/**
 * This class extends AbstractPollingReporter that has a thread pool of one thread
 * which executes the method run. The private methods and the various process methods
 * are run by this thread.
 * onMetricRemoved and onMetricAdded which are derived from MetricsRegistryListener
 * interface are run by the thread that adds a metric or removes it, not from the
 * executor thread.
 */
public class MidoReporter extends AbstractPollingReporter
    implements MetricProcessor<String>, MetricsRegistryListener {

    private final static Logger log = LoggerFactory.getLogger(
        MidoReporter.class);

    /* List of the metrics recently added and that need to be registered in the store
     * Key is type + metricName */
    final private List<MetricName> metricsToStore;
    /* Map of metrics already registered in the store */
    private Map<String, Integer> storedMetrics;
    /* Map of metric types already registered in the store. We will store every
       metrics type available for every target in the store. We can then retrieve
       target-> type, type-> metrics. */
    private Map<MetricName, Integer> storedMetricsTypes;
    private Store store;

    @Inject
    MidoReporter(Store store) {
        // TODO(rossella) MetricsRegistry allow a maximum of 1024 items
        super(Metrics.defaultRegistry(), "MidonetMonitoring");
        metricsToStore = new CopyOnWriteArrayList<MetricName>();
        storedMetrics = new HashMap<String, Integer>();
        storedMetricsTypes = new HashMap<MetricName, Integer>();
        this.store = store;
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
        // metricsToStore is a CopyOnWriteArrayList because it's accessed by
        // addNewMetricsInfoToStore() and onMetricAdded() that are called by different
        // threads. addNewMetricsInfoToStore() is executed by the thread of the reporter
        // (the reporter has an executor). onMetricAdded() is fired when a new metric
        // is added and it's using the thread that ran the add metric code
        for (MetricName metric : metricsToStore) {
            // check if it's a new type, if so we have to store it
            if (!storedMetricsTypes.containsKey(metric)) {
                store.addMetricTypeToTarget(metric.getScope(),
                                            metric.getType());
                log.trace("Added type {} to target {}",
                          new Object[]{metric.getType(), metric.getScope()});
                storedMetricsTypes.put(metric, 1);
            }

            // check if it's a "new" metric, if so we should store it
            if (!storedMetrics.containsKey(
                metric.getType() + metric.getName())) {
                store.addMetricToType(metric.getType(), metric.getName());
                storedMetrics.put(metric.getType() + metric.getName(), 1);
                log.trace("Added metric {} to type {}",
                          new Object[]{metric.getName(), metric.getType()});
            }
        }
        // clear list
        metricsToStore.clear();
    }

    @Override
    public void processGauge(MetricName name, Gauge<?> gauge, String s)
        throws Exception {
        Long val;
        Object originalValue = gauge.value();
        if (originalValue instanceof Number) {
            val = ((Number) originalValue).longValue();
        } else if (originalValue instanceof Boolean) {
            val = (Boolean) originalValue ? 1l : 0l;
        } else {
            log.debug("Unsupported value type {}",
                      originalValue.getClass().getCanonicalName());
            return;
        }

        store.addTSPoint(name.getType(), name.getScope(),
                         name.getName(), System.currentTimeMillis(), val
        );

    }

    @Override
    public void processMeter(MetricName metricName, Metered metered, String s)
        throws Exception {
        Long val = metered.count();

        store.addTSPoint(metricName.getType(), metricName.getScope(),
                         metricName.getName(), System.currentTimeMillis(), val
        );
    }

    @Override
    public void processCounter(MetricName metricName, Counter counter, String s)
        throws Exception {

        Long val = counter.count();

        store.addTSPoint(metricName.getType(), metricName.getScope(),
                         metricName.getName(), System.currentTimeMillis(), val
        );
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
        // This method must return fast, it's called every time a metric is added
        // VifMetrics also fires this event, so if it takes too long we could have
        // some problem in addVirtualPort
        metricsToStore.add(name);
    }

    @Override
    public void onMetricRemoved(MetricName name) {
    }

}
