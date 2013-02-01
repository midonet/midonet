/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package org.midonet.client.dto;

import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

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
    long timeStampStart;
    /**
     * the end point of the time interval we are querying
     */
    long timeStampEnd;

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public void setTimeStampStart(long timeStampStart) {
        this.timeStampStart = timeStampStart;
    }

    public long getTimeStampEnd() {
        return timeStampEnd;
    }

    public void setTimeStampEnd(long timeStampEnd) {
        this.timeStampEnd = timeStampEnd;
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

    public long getTimeStampStart() {
        return timeStampStart;
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
                "start=" + timeStampStart +
                "end=" + timeStampEnd +
                "}";
    }
}
