/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.util.Map;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Date: 5/4/12
 */
@XmlRootElement
public class DtoMetricQueryResponse {

    String interfaceName;
    String metricName;
    long startEpochTime;
    long endEpochTime;
    Map<String, Long> results;
    String granularity;

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
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

    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    public String getInterfaceName() {
        return interfaceName;
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

    public String getGranularity() {
        return granularity;
    }

    @Override
    public String toString() {
        String res = "DtoMetricQueryResponse{" +
                "interfaceName=" + interfaceName +
                "metricName=" + metricName +
                "start=" + startEpochTime +
                "end=" + endEpochTime +
                "type=" + granularity;

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
