/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/26/12
 */
public class VMMetrics extends LocalMetricRegistryFiller {

    private final static String VM_METRIC_NAME = "VMMetrics";
    private static List<MetricRegistryFillerItem> metrics = new ArrayList<MetricRegistryFillerItem>();

    @Override
    public void createMetricsList(){
        metrics.add(new MetricRegistryFillerItem("ThreadCount", "ThreadCount","java.lang:type=Threading"));
    }

    @Override
    public String getMetricsCollectorName() {
        return VM_METRIC_NAME;
    }

    @Override
    public List<MetricRegistryFillerItem> getMetricList() {
        return metrics;
    }

}
