/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

import java.util.List;

import com.yammer.metrics.core.MetricsRegistry;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/27/12
 */
public interface MetricRegistryFiller {

    public void addMetricsToRegistry(MetricsRegistry registry);

    void createMetricsList();

    String getMetricsCollectorName();

    List<MetricRegistryFillerItem> getMetricList();
}
