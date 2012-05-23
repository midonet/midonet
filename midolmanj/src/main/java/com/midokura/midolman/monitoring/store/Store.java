/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import java.util.List;
import java.util.Map;

public interface Store {

    public void addTSPoint(String type, String targetIdentifier,
                           String metricName, long time,
                           long value);

    public long getTSPoint(String type, String targetIdentifier,
                           String metricName, long time);

    public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd);

    public void addMetric(String type, String targetIdentifier,
                          String metricName);

    public List<String> getMetrics(String type, String targetIdentifier);

}