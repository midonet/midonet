/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import java.util.List;
import java.util.ArrayList;
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
                          int expirationSecs) throws HectorException{

        client = new CassandraClient(server, clusterName, keyspaceName,
                                     columnFamily, replicationFactor,
                                     expirationSecs);
    }

    @Override
    public void addTSPoint(String interfaceName, long time, String value,
                           String metricName,
                           String granularity) {
        String key = interfaceName + metricName + GMTTime.getDayMonthYear(
                time) + granularity;
        client.set(key, value, Long.toString(time));
        log.debug("Added value {}, for key {}, column {}",
                 new Object[]{value, key, time});
    }

    @Override
    public String getTSPoint(String interfaceName, long time, String metricName,
                             String granularity) {
        String key = interfaceName + metricName + GMTTime.getDayMonthYear(
                time) + granularity;
        return client.get(key, Long.toString(time));
    }

    @Override
    public Map<String, String> getTSPoint(String interfaceName, long timeStart,
                                          long timeEnd,
                                          String metricName,
                                          String granularity) {
        int numberOfDays = GMTTime.getNumberOfDays(timeStart, timeEnd);

        if (numberOfDays == 0) {
            String key = interfaceName + metricName + GMTTime.getDayMonthYear(
                    timeStart) + granularity;

            return client.executeSliceQuery(key, Long.toString(timeStart),
                                            Long.toString(timeEnd),
                                            maxNumberQueryResult);
        } else {
            long msInADay = 24 * 60 * 60 * 1000;
            List<String> keys = new ArrayList<String>();
            for (int i = 0; i <= numberOfDays; i++) {
                // since we store each day using a different key, calculate
                // the keys
                keys.add(interfaceName + metricName + GMTTime.getDayMonthYear(
                        timeStart + i * msInADay) + granularity);
            }

            return client.executeSliceQuery(keys, Long.toString(timeStart),
                                            Long.toString(timeEnd),
                                            maxNumberQueryResult);
        }
    }

    public static void setMaxNumberQueryResult(int maxNumberQueryResult) {
        CassandraStore.maxNumberQueryResult = maxNumberQueryResult;
    }
}
