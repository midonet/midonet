/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Metric;
import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;

/**
 * Date: 5/4/12
 */
public interface MetricDao {

    MetricQueryResponse executeQuery(MetricQuery query);

    List<Metric> listMetrics(String type, UUID targetIdentifier);
}
