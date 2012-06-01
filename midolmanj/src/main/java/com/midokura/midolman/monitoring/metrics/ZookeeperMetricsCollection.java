/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.gauges.JMXRemoteBeanGauge;

/**
 * Date: 4/25/12
 */
public class ZookeeperMetricsCollection {

    private final static Logger log =
        LoggerFactory.getLogger(JMXRemoteBeanGauge.class);


    private String hostName = "UNKNOWN";

    public ZookeeperMetricsCollection() {
        try {
            //TODO use a unique id, maybe hostUUID?
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Host unknown!", e);
        }
    }

    //TODO(rossella) use Mihai's helper once the code is pushed to master
    private static MBeanServerConnection connectJMXServer(String serverUrl) {
        MBeanServerConnection connection = null;
        try {
            JMXServiceURL url = new JMXServiceURL(serverUrl);
            //Get JMX connector
            JMXConnector jmxc = JMXConnectorFactory.connect(url);
            //Get MBean server connection
            connection = jmxc.getMBeanServerConnection();
        } catch (Exception e) {
            log.error("Couldn't connect to the JMX server {}", serverUrl, e);
        }
        return connection;
    }

    public void registerMetrics(String jmxServerPath) {
        MBeanServerConnection serverConn = connectJMXServer(jmxServerPath);

        if (serverConn == null) {
            log.warn("Could not open JMX server connection to {}", jmxServerPath);
            return;
        }

        registerMetric(serverConn, "PacketsSent", Integer.class,
                       "org.apache.ZooKeeperService:name0=StandaloneServer_port-1",
                       "ZKPacketsSent");
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