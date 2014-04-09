/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.monitoring.store;

import org.midonet.cassandra.CassandraClient;
import org.midonet.util.functors.Callback0;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MockStore implements Store {

    private static int maxNumberQueryResult = 1024;

    private static final Logger log =
            LoggerFactory.getLogger(MockStore.class);

    Map<String, Callback0> callbacks;
    Map<String, Long> points;

    // needed for the tests
    Map<String, Set<String>> target_MetricName;
    Map<String, Set<String>> metricType_targetIdentifier;
    ConcurrentHashMap<String, List<String>> type_metricNames;

    public MockStore()
    {
        callbacks = new ConcurrentHashMap<String, Callback0>();
        points = new ConcurrentHashMap<String, Long>();

        target_MetricName = new ConcurrentHashMap<String, Set<String>>();
        metricType_targetIdentifier = new ConcurrentHashMap<String, Set<String>>();
        type_metricNames = new ConcurrentHashMap<String, List<String>>();

    }

    @Override
    public void initialize() {
    }

    @Override
    public void addTSPoint(String type, String targetIdentifier,
                           String metricName,
                           long time, long value) {

        if (!target_MetricName.containsKey(targetIdentifier)) {
            target_MetricName.put(targetIdentifier, new HashSet<String>());
        }
        target_MetricName.get(targetIdentifier).add(metricName);

        addMetricTypeToTarget(type, targetIdentifier);

        // add the value
        String key = asKey(type, targetIdentifier, metricName, time);
        points.put(key, value);


        // if there is a registered callback for this targetidentifier, call it.
        if (callbacks.containsKey(targetIdentifier)) {
            callbacks.get(targetIdentifier).call();
        }
    }

    @Override
    public long getTSPoint(String type, String targetIdentifier,
                           String metricName, long time) {
        return points.get(asKey(type, targetIdentifier, metricName, time));
    }


    private String asKey(String type, String targetIdentifier,
                         String metricName, long time) {
        return new StringBuffer()
                .append(type)
                .append(targetIdentifier)
                .append(metricName)
                .append(time)
                .toString();
    }

    @Override
    /**
     * Return all values, do not check the key.
     */
    public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd) {
        Map<String, Long> result = new HashMap<String, Long>();
        for (Map.Entry<String, Long> keyValue : points.entrySet()) {
            result.put(keyValue.getKey(), keyValue.getValue());
        }
        return result;
    }


    @Override
    public void addMetricTypeToTarget(String type, String targetIdentifier) {
        if (!metricType_targetIdentifier.containsKey(type)) {
            metricType_targetIdentifier.put(type, new HashSet<String>());
        }
        metricType_targetIdentifier.get(type).add(targetIdentifier);
    }


    @Override
    public List<String> getMetricsTypeForTarget(String targetIdentifier) {
        List<String> result = new ArrayList<String>();
        if (metricType_targetIdentifier.containsKey(targetIdentifier)) {
            result = new ArrayList<String>(metricType_targetIdentifier.get(targetIdentifier));
        }
        return result;
    }

    @Override
    public void addMetricToType(String type,
                                String metricName) {
        if(!type_metricNames.containsKey(type)) {
            log.debug("ADDING METRIC FOR {}", type);
            type_metricNames.putIfAbsent(type, new LinkedList<String>());
        }

        type_metricNames.get(type).add(metricName);
    }

    @Override
    public List<String> getMetricsForType(String type) {
        List<String> result = new ArrayList<String>();
        log.debug("GET METRICS FOR TYPE: {}", type);

        for (String key : type_metricNames.keySet()) {
            log.debug("KEY: " + key);
        }
        if (type_metricNames.containsKey(type)) {
            result = new ArrayList<String>(type_metricNames.get(type));
        }
        return result;
    }

    public static void setMaxNumberQueryResult(int maxNumberQueryResult) {
        MockStore.maxNumberQueryResult = maxNumberQueryResult;
    }

    public Set<String> getMetricNamesForTarget(String target) {
        return target_MetricName.get(target);
    }

    public Set<String> getTargets() {
        return target_MetricName.keySet();
    }

    public void subscribeToChangesRegarding(String id, Callback0 callback) {
        if (!callbacks.containsKey(id)) {
            callbacks.put(id, callback);
        }

    }
}
