/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import com.midokura.cassandra.CassandraClient;
import com.midokura.util.functors.Callback0;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MockStore implements Store {

    Map<String, Set<String>> targetMetric;

    private static int maxNumberQueryResult = 1024;

    private static final Logger log =
            LoggerFactory.getLogger(MockStore.class);

    public Map<String, Callback0> callbacks;

    public MockStore(CassandraClient client)
    {
        targetMetric = new HashMap<String, Set<String>>();
        callbacks = new HashMap<String, Callback0>();
    }

    @Override
    public void initialize() {
    }

    @Override
    public void addTSPoint(String type, String targetIdentifier,
                           String metricName,
                           long time, long value) {
        if (!targetMetric.containsKey(metricName)) {
            targetMetric.put(targetIdentifier, new HashSet<String>());
        }
        targetMetric.get(targetIdentifier).add(metricName);
        if (callbacks.containsKey(targetIdentifier)) {
            callbacks.get(targetIdentifier).call();
        }
    }

    @Override
    public long getTSPoint(String type, String targetIdentifier,
                           String metricName, long time) {
        return 0l;
    }

    private String asKey(String type, String targetIdentifier,
                         String metricName, long time) {
        return "";
    }

    @Override
    public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd) {
        return null;
    }


    @Override
    public void addMetricTypeToTarget(String targetIdentifier, String type) {
    }


    @Override
    public List<String> getMetricsTypeForTarget(String targetIdentifier) {
        return null;
    }

    @Override
    public void addMetricToType(String type,
                                String metricName) {
    }

    @Override
    public List<String> getMetricsForType(String type) {
        return null;
    }

    public static void setMaxNumberQueryResult(int maxNumberQueryResult) {
    }

    public Set<String> getMetricNamesForTarget(String target) {
        return targetMetric.get(target);
    }

    public Set<String> getTargets() {
        return targetMetric.keySet();
    }

    public void subscribeToChangesRegarding(String id, Callback0 callback) {
        if (!callbacks.containsKey(id)) {
            callbacks.put(id, callback);
        }

    }
}
