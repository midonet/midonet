/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import com.midokura.cassandra.CassandraClient;
import com.midokura.midolman.monitoring.GMTTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MockCassandraStore implements Store {

    private static int maxNumberQueryResult = 1024;

    private static final Logger log =
            LoggerFactory.getLogger(MockCassandraStore.class);


    public MockCassandraStore(CassandraClient client)
    {
    }

    @Override
    public void initialize() {
    }

    @Override
    public void addTSPoint(String type, String targetIdentifier,
                           String metricName,
                           long time, long value) {
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
}
