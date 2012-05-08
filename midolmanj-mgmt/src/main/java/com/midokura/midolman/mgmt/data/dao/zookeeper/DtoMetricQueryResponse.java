package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.Map;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/4/12
 */
@XmlRootElement
public class DtoMetricQueryResponse {

    String interfaceName;
    String metricName;
    long startEpochTime;
    long endEpochTime;
    Map<String, String> results;
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

    public void setResults(Map<String, String> results) {
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

    public Map<String, String> getResults() {
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
                "granularity=" + granularity;

        for (Map.Entry<String, String> entry : results.entrySet()) {
            res += "[";
            res += entry.getKey();
            res += ",";
            res += entry.getValue();
            res += "]";
        }

        res += "}";
        return res;
    }
}
