/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.zookeeper.dao;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.dto.Metric;
import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;
import com.midokura.midolman.monitoring.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Dao for querying the metric data in Cassandra
 * Date: 5/4/12
 */
public class MetricCassandraDao implements MetricDao {

    private final static Logger log = LoggerFactory
        .getLogger(MetricCassandraDao.class);

    private final Store store;

    @Inject
    public MetricCassandraDao(Store store) {
        this.store = store;
    }

    /**
     * @param query to execute
     * @return the results of the query
     */
    MetricQueryResponse executeQuery(MetricQuery query) {
        Map<String, Long> results = new HashMap<String, Long>();
        results = store.getTSPoints(query.getType(),
                                    query.getTargetIdentifier().toString(),
                                    query.getMetricName(),
                                    query.getStartEpochTime(),
                                    query.getEndEpochTime()

        );
        MetricQueryResponse response = new MetricQueryResponse();
        response.setMetricName(query.getMetricName());
        response.setTargetIdentifier(query.getTargetIdentifier());
        response.setType(query.getType());
        response.setResults(results);
        response.setTimeStampStart(query.getStartEpochTime());
        response.setTimeStampEnd(query.getEndEpochTime());
        return response;
    }

    /**
     * @param list of queries to execute
     * @return the results of the queries
     */
    @Override
    public List<MetricQueryResponse> executeQueries(List<MetricQuery> queries) {
        List<MetricQueryResponse> results = new ArrayList<MetricQueryResponse>();
        for (MetricQuery query : queries) {
            results.add(executeQuery(query));
        }
        return results;
    }

    /**
     * @param targetIdentifier id of the object whose metrics we want to kno
     * @return available metrics
     */
    @Override
    public List<Metric> listMetrics(UUID targetIdentifier) {
        List<String> metricsTypes = store.getMetricsTypeForTarget(
            targetIdentifier.toString());
        List<Metric> result = new ArrayList<Metric>();
        for(String type : metricsTypes){
            List<String> metrics = store.getMetricsForType(type);
            for (String m : metrics) {
                Metric aMetric = new Metric();
                aMetric.setTargetIdentifier(targetIdentifier);
                aMetric.setName(m);
                aMetric.setType(type);
                result.add(aMetric);
            }
        }
        return result;
    }
}
