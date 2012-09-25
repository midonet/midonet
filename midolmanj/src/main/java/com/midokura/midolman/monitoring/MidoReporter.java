/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.ArrayList;
import java.util.HashMap;
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

import com.midokura.midolman.monitoring.store.Store;

/**
 * Date: 4/24/12
 */
public class MidoReporter extends AbstractPollingReporter
        implements MetricProcessor<String>, MetricsRegistryListener {

    private final static Logger log = LoggerFactory.getLogger(
            MidoReporter.class);

    /* List of the metrics recently added and that need to be registered in the store
     * Key is type + metricName */
    private static List<MetricName> metricsToStore = new ArrayList<MetricName>();
    /* List of new metrics type activated for some target. We will store every metrics type
       available for every target in the store. We can then retrieve target-> type,
       type-> metrics.
     */
    private static List<MetricName> typeTargetToStore = new ArrayList<MetricName>();
    /* List of metrics already registered in the store */
    private static Map<String, Integer> storedMetrics = new HashMap<String, Integer>();

    private Store store;

    public MidoReporter(Store store, String name) {
        // TODO(rossella) MetricsRegistry allow a maximum of 1024 items
        super(Metrics.defaultRegistry(), name);
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
        for (MetricName metric : metricsToStore) {
            // check if it's a "new" metric, if so we should store it
            // TODO(ross) this needs to be modified and use MidoStore
            if (!storedMetrics.containsKey(metric.getType() + metric.getName())) {
                store.addMetricToType(metric.getType(), metric.getName());
                storedMetrics.put(metric.getType() + metric.getName(), 1);
                log.debug("Added metric {} to type {}",
                        new Object[]{metric.getName(), metric.getType()});
            }
        }
        for (MetricName metric : typeTargetToStore) {
            store.addMetricTypeToTarget(metric.getScope(), metric.getType());
            log.debug("Added type {} to target {}",
                    new Object[]{metric.getType(), metric.getScope()});
        }
        // clear list
        metricsToStore.clear();
        typeTargetToStore.clear();
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

    public static void notifyNewMetricTypeForTarget(MetricName metricName) {
        typeTargetToStore.add(metricName);
    }
}
