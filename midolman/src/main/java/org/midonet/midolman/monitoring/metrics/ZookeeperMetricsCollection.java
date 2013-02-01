/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package org.midonet.midolman.monitoring.metrics;

import java.io.IOException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;

import com.google.inject.Inject;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.monitoring.HostKeyService;
import org.midonet.midolman.monitoring.MidoReporter;
import org.midonet.midolman.monitoring.gauges.JMXRemoteBeanGauge;
import org.midonet.util.jmx.JMXHelper;

/**
 * Date: 4/25/12
 */
public class ZookeeperMetricsCollection {

    private final static Logger log =
            LoggerFactory.getLogger(JMXRemoteBeanGauge.class);

    private static final String mBeanZkService =
            "org.apache.ZooKeeperService:name0=StandaloneServer_port-1";
    private static final String mBeanMemoryDataTree =
            mBeanZkService + "," + "name1=InMemoryDataTree";

    @Inject
    HostKeyService hostKeyService;

    private String hostName;

    public void registerMetrics(String jmxServerPath) {

        MBeanServerConnection serverConn;
        try {
            serverConn =
                    JMXHelper.newJmxServerConnectionFromUrl(jmxServerPath);
        } catch (IOException e) {
            log.error("Couldn't connect to the JMX server {}", jmxServerPath,
                    e);
            return;
        }

        hostName = hostKeyService.getHostId();
        registerMetric(serverConn, "ZKPacketsSent", Long.class, mBeanZkService,
                "PacketsSent");

        registerMetric(serverConn, "ZKPacketsReceived", Long.class,
                mBeanZkService, "PacketsReceived");

        registerMetric(serverConn, "ZKAvgRequestLatency", Long.class,
                mBeanZkService,
                "AvgRequestLatency");

        registerMetric(serverConn, "ZKNodeCount", Integer.class,
                mBeanMemoryDataTree,
                "NodeCount");

        registerMetric(serverConn, "ZKWatchCount", Integer.class,
                mBeanMemoryDataTree,
                "WatchCount");

    }

    private <T> void registerMetric(MBeanServerConnection serverConn,
                                    String name, Class<T> type,
                                    String beanName, String beanAttr) {

        try {
            Metrics.newGauge(
                    new MetricName(ZookeeperMetricsCollection.class, name,
                            hostName),
                    new JMXRemoteBeanGauge<T>(serverConn,
                            type, beanName, beanAttr));
        } catch (MalformedObjectNameException e) {
            log.debug(
                    "Malformed Exception while trying to add a JMXRemoteBeanGauge",
                    e);
        }

    }
}
