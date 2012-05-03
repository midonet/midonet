/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;

import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/27/12
 */
public abstract class RemoteMetricRegistryFiller implements MetricRegistryFiller{

    private final static Logger log =
            LoggerFactory.getLogger(RemoteMetricRegistryFiller.class);

    MBeanServerConnection mBeanServerConnection;

    protected RemoteMetricRegistryFiller(
            MBeanServerConnection mBeanServerConnection) {
        this.mBeanServerConnection = mBeanServerConnection;
    }

    public void addMetricsToRegistry(MetricsRegistry registry) {
        createMetricsList();
        for(MetricRegistryFillerItem item : getMetricList()){

            try {
                JMXServerConnectionGauge gauge = new JMXServerConnectionGauge(item.getmBean(), item.getAttribute(), mBeanServerConnection);
                MetricName metricName = new MetricName(getMetricsCollectorName(), item.getType(), item.getKey());
                registry.newGauge(metricName, gauge);
            } catch (MalformedObjectNameException e) {
                log.error("Malformed Object", e);
            }
        }
    }

}
