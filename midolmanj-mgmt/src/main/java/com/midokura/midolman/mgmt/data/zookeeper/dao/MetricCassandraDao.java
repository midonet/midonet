/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.dto.Metric;
import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;
import com.midokura.midolman.monitoring.store.CassandraStore;

/**
 * Dao for querying the metric data in Cassandra
 * Date: 5/4/12
 */
public class MetricCassandraDao implements MetricDao {

    private final static Logger log = LoggerFactory
        .getLogger(MetricCassandraDao.class);

    private final CassandraStore store;

    public MetricCassandraDao(CassandraStore store) {
        this.store = store;
    }

    /**
     * @param query to execute
     * @return the results of the query
     */
    @Override
    public MetricQueryResponse executeQuery(MetricQuery query) {
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
        return response;
    }

    /**
     * @param type             type of the metric
     * @param targetIdentifier id of the object whose metrics we want to kno
     * @return available metrics
     */
    @Override
    public List<Metric> listMetrics(String type, UUID targetIdentifier) {
        List<String> metrics = store.getMetrics(type,
                                                targetIdentifier.toString());
        List<Metric> result = new ArrayList<Metric>();
        for (String m : metrics) {
            Metric aMetric = new Metric();
            aMetric.setTargetIdentifier(targetIdentifier);
            aMetric.setName(m);
            result.add(aMetric);
        }
        return result;
    }
}
