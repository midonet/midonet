/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.mgmt.data.dto;

import java.util.Map;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/3/12
 */
@XmlRootElement
public class MetricQueryResponse extends UriResource {

    String interfaceName;
    String metricName;
    long timeStampStart;
    long timeStampEnd;
    Map<String, Long> results;
    String granularity;


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

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
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

    public long getTimeStampStart() {
        return timeStampStart;
    }

    public long getTimeStampEnd() {
        return timeStampEnd;
    }

    public Map<String, Long> getResults() {
        return results;
    }

    public String getGranularity() {
        return granularity;
    }
}
