/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;

import com.google.inject.Inject;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.HostKeyService;
import com.midokura.midolman.monitoring.MidoReporter;
import com.midokura.midolman.monitoring.gauges.JMXRemoteBeanGauge;

/**
 * This metrics collection will create Gauges for the the interesting VM metrics
 * available via local JMX beans.
 * <p/>
 * Date: 4/26/12
 */
public class VMMetricsCollection {

    private final static Logger log =
            LoggerFactory.getLogger(VMMetricsCollection.class);

    private static final MBeanServer SERVER =
            ManagementFactory.getPlatformMBeanServer();

    private static int metricsCount = 0;

    @Inject
    HostKeyService hostKeyService;

    String hostName;

    public void registerMetrics() {

        hostName = hostKeyService.getHostId();

        MidoReporter.notifyNewMetricTypeForTarget(new MetricName(VMMetricsCollection.class, "", hostName));

        addLocalJmxPoolingMetric("ThreadCount", Integer.class,
                "java.lang:type=Threading", "ThreadCount");

        addLocalJmxPoolingMetric("ProcessCPUTime", Long.class,
                "java.lang:type=OperatingSystem",
                "ProcessCpuTime");

        addLocalJmxPoolingMetric("FreePhysicalMemorySize", Long.class,
                "java.lang:type=OperatingSystem",
                "FreePhysicalMemorySize");

        addLocalJmxPoolingMetric("FreeSwapSpaceSize", Long.class,
                "java.lang:type=OperatingSystem",
                "FreeSwapSpaceSize");

        addLocalJmxPoolingMetric("TotalSwapSpaceSize", Long.class,
                "java.lang:type=OperatingSystem",
                "TotalSwapSpaceSize");

        addLocalJmxPoolingMetric("TotalPhysicalMemorySize", Long.class,
                "java.lang:type=OperatingSystem",
                "TotalPhysicalMemorySize");

        addLocalJmxPoolingMetric("OpenFileDescriptorCount", Long.class,
                "java.lang:type=OperatingSystem",
                "OpenFileDescriptorCount");

        addLocalJmxPoolingMetric("AvailableProcessors", Integer.class,
                "java.lang:type=OperatingSystem",
                "AvailableProcessors");

        addLocalJmxPoolingMetric("SystemLoadAverage", Double.class,
                "java.lang:type=OperatingSystem",
                "SystemLoadAverage");

        addLocalJmxPoolingMetric("CommittedHeapMemory", Long.class,
                "java.lang:type=Memory", "HeapMemoryUsage",
                "committed");

        addLocalJmxPoolingMetric("MaxHeapMemory", Long.class,
                "java.lang:type=Memory", "HeapMemoryUsage",
                "max");

        addLocalJmxPoolingMetric("UsedHeapMemory", Long.class,
                "java.lang:type=Memory", "HeapMemoryUsage",
                "used");
    }

    private <T> void addLocalJmxPoolingMetric(String name, Class<T> type,
                                              String beanName,
                                              String beanAttr) {
        try {
            addMetric(name,
                    new JMXRemoteBeanGauge<T>(SERVER, type, beanName,
                            beanAttr)
            );
        } catch (MalformedObjectNameException e) {
            log.error("could not create that JMXRemoteBeanGauge", e);
        }
    }

    private <T> void addLocalJmxPoolingMetric(String name, Class<T> type,
                                              String beanName, String beanAttr,
                                              String compositeKeyName) {
        try {
            addMetric(name,
                    new JMXRemoteBeanGauge<T>(SERVER,
                            type, beanName, beanAttr,
                            compositeKeyName)
            );
        } catch (MalformedObjectNameException e) {
            log.error("could not create that JMXRemoteBeanGauge", e);
        }
    }

    private <T> void addMetric(String name, Gauge<T> gauge) {
        Metrics.newGauge(
                new MetricName(VMMetricsCollection.class, name, hostName),
                gauge);

        metricsCount++;
    }

    public static int getMetricsCount() {
        return metricsCount;
    }
}
