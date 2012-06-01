/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.gauges.JMXRemoteBeanGauge;
import com.midokura.midolman.monitoring.gauges.TranslatorGauge;
import com.midokura.util.functors.Functor;

/**
 * Date: 4/26/12
 */
public class VMMetricsCollection {

    private final static Logger log =
        LoggerFactory.getLogger(VMMetricsCollection.class);

    private static final MBeanServer SERVER =
        ManagementFactory.getPlatformMBeanServer();

    private String hostName = "UNKNOWN";

    public VMMetricsCollection() {
        try {
            //TODO use a unique id, maybe hostUUID?
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Host unknown!", e);
        }
    }

    public void registerMetrics() {
        addLocalJmxPoolingMetric("ThreadCount", Integer.class,
                                 "java.lang:type=Threading", "ThreadCount");

        addLocalJmxPoolingMetric("ThreadCount", Integer.class,
                                 "java.lang:type=Threading", "ThreadCount");

        addLocalJmxPoolingMetric("ProcessCPUTime", Long.class,
                                 "java.lang:Type=OperatingSystem",
                                 "ProcessCPUTime");

        addLocalJmxPoolingMetric("FreePhysicalMemorySize", Long.class,
                                 "java.lang:Type=OperatingSystem",
                                 "FreePhysicalMemorySize");

        addLocalJmxPoolingMetric("FreeSwapSpaceSize", Long.class,
                                 "java.lang:Type=OperatingSystem",
                                 "FreeSwapSpaceSize");

        addLocalJmxPoolingMetric("TotalSwapSpaceSize", Long.class,
                                 "java.lang:Type=OperatingSystem",
                                 "TotalSwapSpaceSize");

        addLocalJmxPoolingMetric("TotalPhysicalMemorySize", Long.class,
                                 "java.lang:Type=OperatingSystem",
                                 "TotalPhysicalMemorySize");

        addLocalJmxPoolingMetric("OpenFileDescriptorCount", Long.class,
                                 "java.lang:Type=OperatingSystem",
                                 "OpenFileDescriptorCount");

        addLocalJmxPoolingMetric("AvailableProcessors", Integer.class,
                                 "java.lang:Type=OperatingSystem",
                                 "AvailableProcessors");

        addLocalJmxPoolingMetric("SystemLoadAverage", Double.class,
                                 "java.lang:Type=OperatingSystem",
                                 "SystemLoadAverage");

        addMemoryMetric("CommittedHeapMemory",
                        "java.lang:type=Memory", "HeapMemoryUsage",
                        new Functor<MemoryUsage, Long>() {
                            @Override
                            public Long apply(MemoryUsage arg0) {
                                return arg0.getCommitted();
                            }
                        });

        addMemoryMetric("MaxHeapMemory",
                        "java.lang:type=Memory", "HeapMemoryUsage",
                        new Functor<MemoryUsage, Long>() {
                            @Override
                            public Long apply(MemoryUsage arg0) {
                                return arg0.getMax();
                            }
                        });

        addMemoryMetric("UsedHeapMemory",
                        "java.lang:type=Memory", "HeapMemoryUsage",
                        new Functor<MemoryUsage, Long>() {
                            @Override
                            public Long apply(MemoryUsage arg0) {
                                return arg0.getUsed();
                            }
                        });
    }

    private void addMemoryMetric(String name,
                                 String beanName, String beanAttribute,
                                 Functor<MemoryUsage, Long> translator) {
        try {
            addMetric(name,
                      new TranslatorGauge<MemoryUsage, Long>(
                          new JMXRemoteBeanGauge<MemoryUsage>(
                              SERVER,
                              MemoryUsage.class, beanName, beanAttribute),
                          translator
                      ));
        } catch (MalformedObjectNameException e) {
            log.error("Could not create Jmx", e);
        }
    }

    private <T> void addLocalJmxPoolingMetric(String name, Class<T> type,
                                              String beanName,
                                              String beanAttr) {
        try {
            addMetric(name,
                      new JMXRemoteBeanGauge<T>(SERVER,
                                                type, beanName, beanAttr)
            );
        } catch (MalformedObjectNameException e) {
            log.error("Could not create Jmx", e);
        }
    }

    private <T> void addMetric(String name, Gauge<T> gauge) {
        Metrics.newGauge(
            new MetricName(VMMetricsCollection.class, name, hostName),
            gauge);
    }
}
