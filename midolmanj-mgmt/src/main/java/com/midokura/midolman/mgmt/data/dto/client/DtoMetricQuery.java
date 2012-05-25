/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto.client;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Date: 5/4/12
 */
@XmlRootElement
public class DtoMetricQuery {

    String metricName;
    long startEpochTime;
    private long endEpochTime;
    String interfaceName;
    String type;

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

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
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

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        return "DtoMetricQuery{" +
                "type=" + type +
                "interfaceName=" + interfaceName +
                "metricName=" + metricName +
                "start=" + startEpochTime +
                "end=" + endEpochTime +
                "}";
    }
}
