package com.midokura.midolman.mgmt.data.dao.zookeeper;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/4/12
 */
@XmlRootElement
public class DtoMetricQuery {

    String metricName;
    long startEpochTime;
    private long endEpochTime;
    String interfaceName;
    String granularity;

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

    public void setGranularity(String granularity) {
        this.granularity = granularity;
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

    public String getGranularity() {
        return granularity;
    }

    @Override
    public String toString() {
        return "DtoMetricQuery{" +
                "interfaceName=" + interfaceName +
                "metricName=" + metricName +
                "start=" + startEpochTime +
                "end=" + endEpochTime +
                "granularity=" + granularity +
                "}";
    }
}
