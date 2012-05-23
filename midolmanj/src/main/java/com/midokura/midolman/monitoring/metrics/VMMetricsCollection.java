/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/26/12
 */
public class VMMetricsCollection {

    private final static Logger log =
            LoggerFactory.getLogger(VMMetricsCollection.class);


    private static final MBeanServer SERVER = ManagementFactory.getPlatformMBeanServer();

    public static void addMetricsToRegistry() {
        String hostName = "UNKNOWN";
        try {
            //TODO use a unique id, maybe hostUUID?
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Host unknown!", e);
        }
        try {
            MetricName metricName = new MetricName(VMMetricsCollection.class,
                                                   "ThreadCount", hostName);
            Metrics.newGauge(metricName, new JMXRemoteBeanGauge<Integer>(
                    SERVER,
                    "java.lang:type=Threading", "ThreadCount", Integer.class));

        } catch (MalformedObjectNameException e) {
            log.error("Malformed object", e);
        }
    }
}
