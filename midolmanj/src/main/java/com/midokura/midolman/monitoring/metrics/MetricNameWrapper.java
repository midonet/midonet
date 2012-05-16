package com.midokura.midolman.monitoring.metrics;

import com.yammer.metrics.core.MetricName;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/22/12
 */
public class MetricNameWrapper extends MetricName {

    // Default value
    private String granularity = "1s";
    private String targetIdentifier;


    public MetricNameWrapper(String targetUUID, String type, String name,
                             String parentObjectUUID) {
        super(parentObjectUUID, type, name, targetUUID);
        generateTargetIdentifier();
    }

    public MetricNameWrapper(MetricName metricName) {
        super(metricName.getGroup(), metricName.getType(), metricName.getName(),
              metricName.getScope());
        generateTargetIdentifier();
    }

    public String getTargetUUID() {
        return getScope();
    }

    public String getParentObjectUUID() {
        return getGroup();
    }

    @Override
    public String getName() {
        return super.getName() + granularity;
    }

    public String getGranularity() {
        return granularity;
    }

    public String getTargetIdentifier() {
        return targetIdentifier;
    }

    public void generateTargetIdentifier() {
        // some target don't have a parent object, like ZK metrics for example
        if (getParentObjectUUID() == null || getParentObjectUUID().isEmpty())
            targetIdentifier = getTargetUUID();
        else {
            targetIdentifier = getParentObjectUUID() + getTargetUUID();
        }
    }

}
