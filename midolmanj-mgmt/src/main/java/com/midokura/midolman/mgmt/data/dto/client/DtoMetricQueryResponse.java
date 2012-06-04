/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.util.Map;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * This class represents the query results.
 * Date: 5/4/12
 */
@XmlRootElement
public class DtoMetricQueryResponse {

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
    /**
     * results of the query
     */
    Map<String, Long> results;

    public void setTargetIdentifier(UUID targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
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

    public void setResults(Map<String, Long> results) {
        this.results = results;
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

    public long getStartEpochTime() {
        return startEpochTime;
    }

    public long getEndEpochTime() {
        return endEpochTime;
    }

    public Map<String, Long> getResults() {
        return results;
    }

    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        String res = "DtoMetricQueryResponse{" +
            "targetIdentifier=" + targetIdentifier +
            "metricName=" + metricName +
            "start=" + startEpochTime +
            "end=" + endEpochTime +
            "type=" + type;

        for (Map.Entry<String, Long> entry : results.entrySet()) {
            res += "[";
            res += entry.getKey();
            res += ",";
            res += entry.getValue().toString();
            res += "]";
        }

        res += "}";
        return res;
    }
}
