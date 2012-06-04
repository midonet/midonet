/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto;

import java.util.Map;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * This class represents the query results.
 * Date: 5/3/12
 */
@XmlRootElement
public class MetricQueryResponse extends UriResource {

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
    /**
     * results of the query
     */
    Map<String, Long> results;


    public MetricQueryResponse() {
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public void setTimeStampStart(long timeStampStart) {
        this.timeStampStart = timeStampStart;
    }

    public void setTimeStampEnd(long timeStampEnd) {
        this.timeStampEnd = timeStampEnd;
    }

    public void setResults(Map<String, Long> results) {
        this.results = results;
    }

    public void setTargetIdentifier(UUID targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public void setType(String type) {
        this.type = type;
    }

    public UUID getTargetIdentifier() {
        return targetIdentifier;
    }

    public String getMetricName() {
        return metricName;
    }

    public long getTimeStampStart() {
        return timeStampStart;
    }

    public long getTimeStampEnd() {
        return timeStampEnd;
    }

    public Map<String, Long> getResults() {
        return results;
    }

    public String getType() {
        return type;
    }
}
