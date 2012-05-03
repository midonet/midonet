/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.yammer.metrics.core.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.metrics.VMMetrics;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetrics;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/25/12
 */
public class MonitoringAgent {
    private final static Logger log =
            LoggerFactory.getLogger(MonitoringAgent.class);

    public static final String ZKJMXPATH = "ZkJMX";

    private String zkJMXServerPath;

    @Inject
    private Store store;

    @Inject
    public MonitoringAgent(@Named(ZKJMXPATH) String zkJMXPAth) {
        this.zkJMXServerPath = zkJMXPAth;
    }

    public void startMonitoring(long pollingTime, TimeUnit timeUnit){
        MetricsRegistry registry = new MetricsRegistry();
        // With no store we won't be able to write the monitoring data
        if(store == null)
            return;
        MidoReporter reporter = new MidoReporter(store, registry, "MidonetMonitoring");
        VMMetrics vmMetrics = new VMMetrics();
        vmMetrics.addMetricsToRegistry(registry);
        ZookeeperMetrics zkMetrics = new ZookeeperMetrics(connectJMXServer(zkJMXServerPath));
        zkMetrics.addMetricsToRegistry(registry);
        reporter.start(pollingTime, timeUnit);
    }

    private MBeanServerConnection connectJMXServer(String serverUrl){
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
