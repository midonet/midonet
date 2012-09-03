/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.monitoring;

import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * This class represents a query to the monitoring system.
 * Date: 5/4/12
 */
@XmlRootElement
public class MetricQuery {

    /**
     * the id of the object for which we collected the metric
     */
    UUID targetIdentifier;
    /**
     * name of the metric
     */
    String metricName;
    /**
     * the type of the metric
     */
    String type;
    /**
     * the starting point of the time interval we are querying
     */
    long startEpochTime;
    /**
     * the end point of the time interval we are querying
     */
    long endEpochTime;

    public MetricQuery() {
    }

    public String getMetricName() {
        return metricName;
    }

    public long getStartEpochTime() {
        return startEpochTime;
    }

    public long getEndEpochTime() {
        return endEpochTime;
    }

    public UUID getTargetIdentifier() {
        return targetIdentifier;
    }

    public String getType() {
        return type;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public void setStartEpochTime(long startEpochTime) {
        this.startEpochTime = startEpochTime;
    }

    public void setEndEpochTime(long endEpochTime) {
        this.endEpochTime = endEpochTime;
    }

    public void setTargetIdentifier(UUID targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public void setType(String type) {
        this.type = type;
    }
}
