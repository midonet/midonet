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

/**
 * Date: 4/25/12
 */
public class ZookeeperMetricsCollection {

    private final static Logger log =
            LoggerFactory.getLogger(JMXRemoteBeanGauge.class);


    public static void addMetricsToRegistry(String mBeanServerUrl) {
        MBeanServerConnection serverConnection = connectJMXServer(
                mBeanServerUrl);
        String hostName = "UNKNOWN";
        try {
            //TODO use a unique id, maybe hostUUID?
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Host unknown!", e);
        }
        try {
            MetricName metricName = new MetricName(
                    ZookeeperMetricsCollection.class,
                    "PacketsSent",
                    hostName);

            Metrics.newGauge(metricName, new JMXRemoteBeanGauge<Integer>(
                    serverConnection,
                    "org.apache.ZooKeeperService:name0=StandaloneServer_port-1",
                    "ZKPacketsSent", Integer.class));

        } catch (MalformedObjectNameException e) {
            log.debug(
                    "Malformed Exception while trying to add a JMXRemoteBeanGauge",
                    e);
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

}