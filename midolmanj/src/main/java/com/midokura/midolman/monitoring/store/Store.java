/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import java.util.List;
import java.util.Map;

public interface Store {

    public void addTSPoint(String targetIdentifier, long time, long value,
                           String metricName);

    public long getTSPoint(String targetIdentifier, long time,
                           String metricName);

    public Map<String, Long> getTSPoints(String targetIdentifier,
                                         long timeStart,
                                         long timeEnd, String metricName);

    public void addMetric(String targetIdentifier, String metricName);

    public String getMetric(String targetIdentifier);

    public void addGranularity(String targetIdentifier, String metricName,
                               String granularity);

    public List<Long> getAvailableGranularities(String targetIdentifier,
                                                String metricName);

}