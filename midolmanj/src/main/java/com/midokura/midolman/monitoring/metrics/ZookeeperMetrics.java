/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServerConnection;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/25/12
 */
public class ZookeeperMetrics extends RemoteMetricRegistryFiller{

    private final static String ZK_METRIC_NAME = "Zookeeper";
    private static List<MetricRegistryFillerItem> metrics = new ArrayList<MetricRegistryFillerItem>();

    public ZookeeperMetrics(MBeanServerConnection mBeanServerConnection) {
        super(mBeanServerConnection);
    }

    @Override
    public void createMetricsList(){
        metrics.add(new MetricRegistryFillerItem("ZKPacketsSent", "PacketsSent","org.apache.ZooKeeperService:name0=StandaloneServer_port-1"));
    }

    @Override
    public String getMetricsCollectorName() {
        return ZK_METRIC_NAME;
    }

    @Override
    public List<MetricRegistryFillerItem> getMetricList() {
        return metrics;
    }

}