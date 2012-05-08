/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;
import com.midokura.midolman.monitoring.store.CassandraStore;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/4/12
 */
public class MetricCassandraDao implements MetricDao {

    private final static Logger log = LoggerFactory.getLogger(
            MetricDao.class);

    private final CassandraStore store;

    public MetricCassandraDao(CassandraStore store) {
        this.store = store;
    }

    @Override
    public MetricQueryResponse executeQuery(MetricQuery query) {
        Map<String, String> results = new HashMap<String, String>();
        results = store.getTSPoint(query.getInterfaceName(),
                                   query.getStartEpochTime(),
                                   query.getEndEpochTime(),
                                   query.getMetricName(),
                                   query.getGranularity());
        MetricQueryResponse response = new MetricQueryResponse();
        response.setMetricName(query.getMetricName());
        response.setInterfaceName(query.getInterfaceName());
        response.setGranularity(query.getGranularity());
        response.setResults(results);
        return response;
    }
}
