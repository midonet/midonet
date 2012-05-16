/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.yammer.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/25/12
 */
public class ZookeeperMetricsCollection {

    private final static String ZK_METRIC_NAME = "Zookeeper";
    private static final String TYPE = "ZK";

    private final static Logger log =
            LoggerFactory.getLogger(JMXServerConnectionGauge.class);


    public static void addMetricsToRegistry(String mBeanServerUrl) {
        MBeanServerConnection serverConnection = connectJMXServer(
                mBeanServerUrl);

        try {
            MetricNameWrapper metricName = new MetricNameWrapper(ZK_METRIC_NAME,
                                                                 TYPE,
                                                                 "PacketsSent",
                                                                 "");
            JMXServerConnectionGauge gauge = new JMXServerConnectionGauge(
                    serverConnection,
                    "org.apache.ZooKeeperService:name0=StandaloneServer_port-1",
                    "ZKPacketsSent");
            Metrics.newGauge(metricName, gauge);

        } catch (MalformedObjectNameException e) {
            log.debug(
                    "Malformed Exception while trying to add a JMXServerConnectionGauge",
                    e);
        }
    }

    public String getMetricsCollectorName() {
        return ZK_METRIC_NAME;
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

}