/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import javax.management.MalformedObjectNameException;

import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.util.JmxGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/27/12
 */
public abstract class LocalMetricRegistryFiller implements MetricRegistryFiller{

    private final static Logger log =
            LoggerFactory.getLogger(LocalMetricRegistryFiller.class);

    public void addMetricsToRegistry(MetricsRegistry registry){
        createMetricsList();
        for(MetricRegistryFillerItem item : getMetricList()){
            JmxGauge jmxGauge;
            try {
                jmxGauge = new JmxGauge(item.getmBean(), item.getAttribute());
                MetricName metricName = new MetricName(getMetricsCollectorName(), item.getType(), item.getKey());
                registry.newGauge(metricName, jmxGauge);

            } catch (MalformedObjectNameException e) {
                log.error("Metric {}, MBean {}", new Object[]{item.getAttribute(), item.getmBean()}, e);
            }
        }
    }
}
