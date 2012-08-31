/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

/**
 * This class represents a query to the monitoring system.
 * Date: 5/4/12
 */
@XmlRootElement
public class DtoMetricQuery {

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

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public void setStartEpochTime(long startEpochTime) {
        this.startEpochTime = startEpochTime;
    }

    public long getEndEpochTime() {
        return endEpochTime;
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

    public String getMetricName() {
        return metricName;
    }

    public long getStartEpochTime() {
        return startEpochTime;
    }

    public UUID getTargetIdentifier() {
        return targetIdentifier;
    }

    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        return "DtoMetricQuery{" +
                "type=" + type +
                "targetIdentifier=" + targetIdentifier +
                "metricName=" + metricName +
                "start=" + startEpochTime +
                "end=" + endEpochTime +
                "}";
    }
}
