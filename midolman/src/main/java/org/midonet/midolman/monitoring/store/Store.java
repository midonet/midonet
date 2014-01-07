/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.monitoring.store;

import java.util.List;
import java.util.Map;

public interface Store {

    public void initialize();

    public void addTSPoint(String type, String targetIdentifier,
                           String metricName, long time,
                           long value);

    public long getTSPoint(String type, String targetIdentifier,
                           String metricName, long time);

    public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd);

    public void addMetricTypeToTarget(String targetIdentifier, String type);

    public List<String> getMetricsTypeForTarget(String targetIdentifier);

    public void addMetricToType(String type,
                                String metricName);

    public List<String> getMetricsForType(String type);

}
