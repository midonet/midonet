/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/4/12
 */
public interface MetricDao {

    MetricQueryResponse executeQuery(MetricQuery query);
}
