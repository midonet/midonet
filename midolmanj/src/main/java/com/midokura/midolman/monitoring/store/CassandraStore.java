/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.prettyprint.hector.api.exceptions.HectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.GMTTime;
import com.midokura.midolman.util.CassandraClient;

public class CassandraStore implements Store {

    private static int maxNumberQueryResult = 1024;

    private static final Logger log =
            LoggerFactory.getLogger(CassandraStore.class);

    private CassandraClient client;

    public CassandraStore(String server, String clusterName,
                          String keyspaceName,
                          String columnFamily, int replicationFactor,
                          int expirationSecs) throws HectorException {

        client = new CassandraClient(server, clusterName, keyspaceName,
                                     columnFamily, replicationFactor,
                                     expirationSecs);
    }

    @Override
    public void addTSPoint(String type, String targetIdentifier,
                           String metricName, long time,
                           long value) {
        String key = targetIdentifier + type + metricName + GMTTime.getDayMonthYear(
                time);
        client.set(key, Long.toString(value), Long.toString(time));
        log.debug("Added value {}, for key {}, column {}",
                  new Object[]{value, key, time});
    }

    @Override
    public long getTSPoint(String type, String targetIdentifier,
                           String metricName, long time) {
        String key = targetIdentifier + type + metricName + GMTTime.getDayMonthYear(
                time);
        return Long.parseLong(client.get(key, Long.toString(time)));
    }

    @Override
    public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                         String metricName, long timeStart,
                                         long timeEnd) {
        int numberOfDays = GMTTime.getNumberOfDays(timeStart, timeEnd);

        if (numberOfDays == 0) {
            String key = targetIdentifier + type + metricName + GMTTime.getDayMonthYear(
                    timeStart);

            return client.executeSliceQuery(key, Long.toString(timeStart),
                                            Long.toString(timeEnd), Long.class,
                                            maxNumberQueryResult);
        } else {
            long msInADay = 24 * 60 * 60 * 1000;
            List<String> keys = new ArrayList<String>();
            for (int i = 0; i <= numberOfDays; i++) {
                // since we store each day using a different key, calculate
                // the keys
                keys.add(
                        targetIdentifier + type + metricName + GMTTime.getDayMonthYear(
                                timeStart + i * msInADay));
            }

            return client.executeSliceQuery(keys, Long.toString(timeStart),
                                            Long.toString(timeEnd), Long.class,
                                            maxNumberQueryResult);
        }
    }

    @Override
    public void addMetric(String type, String targetIdentifier,
                          String metricName) {
        //TODO use another columnfamily?
        client.set(targetIdentifier + type, metricName, metricName);
    }

    @Override
    public List<String> getMetrics(String type, String targetIdentifier) {
        return client.getAllColumnsValues(targetIdentifier + type, String.class,
                                          maxNumberQueryResult);
    }

    public static void setMaxNumberQueryResult(int maxNumberQueryResult) {
        CassandraStore.maxNumberQueryResult = maxNumberQueryResult;
    }
}
