/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;

import com.yammer.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/26/12
 */
public class VMMetricsCollection {

    private final static Logger log =
            LoggerFactory.getLogger(VMMetricsCollection.class);

    private final static String VM_METRIC_NAME = "VMMetricsCollection";
    private static final MBeanServer SERVER = ManagementFactory.getPlatformMBeanServer();
    private static final String TYPE = "VM";


    public static void addMetricsToRegistry() {
        try {

            MetricNameWrapper metricName = new MetricNameWrapper(VM_METRIC_NAME,
                                                                 TYPE,
                                                                 "ThreadCount",
                                                                 "");
            JMXServerConnectionGauge gauge = new JMXServerConnectionGauge(
                    SERVER,
                    "java.lang:type=Threading", "ThreadCount");
            Metrics.newGauge(metricName, gauge);

        } catch (MalformedObjectNameException e) {
            log.error("Malformed object", e);
        }
    }

    public static String getMetricsCollectorName() {
        return VM_METRIC_NAME;
    }
}
