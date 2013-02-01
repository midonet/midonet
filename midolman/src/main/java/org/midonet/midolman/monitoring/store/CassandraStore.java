/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.monitoring.store;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.monitoring.GMTTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CassandraStore implements Store {

    private static int maxNumberQueryResult = 1024;

    private static final Logger log =
            LoggerFactory.getLogger(CassandraStore.class);

    private CassandraClient client;

    public CassandraStore(CassandraClient client)
    {
        if (client == null) {
            throw new IllegalArgumentException("client cannot be null");
        }

        // Client is instantiated but is not connected.  You must connect
        // yourself by calling initialize()
        this.client = client;
    }

    @Override
    public void initialize() {
        client.connect();
    }

    @Override
    public void addTSPoint(String type, String targetIdentifier,
                           String metricName,
                           long time, long value) {
        String key = asKey(type, targetIdentifier, metricName, time);
        client.set(key, Long.toString(value), Long.toString(time));

        log.trace("Added value {}, for key {}, column {}",
                new Object[]{value, key, time});
    }

    @Override
    public long getTSPoint(String type, String targetIdentifier,
                           String metricName, long time) {
        String key = asKey(type, targetIdentifier, metricName, time);
        return Long.parseLong(client.get(key, Long.toString(time)));
    }

    private String asKey(String type, String targetIdentifier,
                         String metricName, long time) {
        return
                targetIdentifier + type + metricName +
                        GMTTime.getDayMonthYear(time);
    }

    @Override
    public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd) {
        int numberOfDays = GMTTime.getNumberOfDays(timeStart, timeEnd);

        if (numberOfDays == 0) {
            String key = asKey(type, targetIdentifier, metricName, timeStart);

            return client.executeSliceQuery(key, Long.toString(timeStart),
                    Long.toString(timeEnd), Long.class,
                    maxNumberQueryResult);
        } else {
            long perDayMillis = TimeUnit.DAYS.toMillis(1);
            List<String> keys = new ArrayList<String>();
            for (int i = 0; i <= numberOfDays; i++) {
                // since we store each day using a different key, calculate
                // the keys
                keys.add(asKey(type, targetIdentifier, metricName,
                        timeStart + i * perDayMillis));
            }

            return client.executeSliceQuery(keys, Long.toString(timeStart),
                    Long.toString(timeEnd), Long.class,
                    maxNumberQueryResult);
        }
    }

    @Override
    public void addMetricTypeToTarget(String targetIdentifier, String type) {
        client.set(targetIdentifier, type, type);
    }

    @Override
    public List<String> getMetricsTypeForTarget(String targetIdentifier) {
        return client.getAllColumnsValues(targetIdentifier, String.class,
                maxNumberQueryResult);
    }

    @Override
    public void addMetricToType(String type,
                                String metricName) {
        //TODO use another columnfamily?
        client.set(type, metricName, metricName);
    }

    @Override
    public List<String> getMetricsForType(String type) {
        return client.getAllColumnsValues(type, String.class,
                maxNumberQueryResult);
    }

    public static void setMaxNumberQueryResult(int maxNumberQueryResult) {
        CassandraStore.maxNumberQueryResult = maxNumberQueryResult;
    }
}
