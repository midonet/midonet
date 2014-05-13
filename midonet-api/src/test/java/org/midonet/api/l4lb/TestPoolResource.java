/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ServiceUnavailableHttpException;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.MappingViolationException;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.HashSet;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestPoolResource extends L4LBResourceTestBase {
    private Pool pool;

    @Before
    public void setUp() throws Exception {
        pool = getStockPool(UUID.randomUUID());
        super.setUp();
    }

    @Test(expected = BadRequestHttpException.class)
    public void testPoolUpdateWithAnotherHealthMonitorId() throws Exception {
        // Assume the model passed the validation.
        doReturn(new HashSet<>()).when(validator).validate(any());
        // Emulate the Pool-HealthMonitorMapping violation.
        doThrow(new MappingViolationException()).when(dataClient)
                .poolUpdate(any(
                        org.midonet.cluster.data.l4lb.Pool.class));

        // If users try to update a pool which is already associated with a
        // health monitor populating the ID of another health monitor, 400
        // Bad Request would be returned.
        HealthMonitor anotherHealthMonitor = getStockHealthMonitor();
        pool.setHealthMonitorId(anotherHealthMonitor.getId());
        poolResource.update(pool.getId(), pool);
    }

    @Test(expected = ServiceUnavailableHttpException.class)
    public void testServiceUnavailableWithAnotherHealthMonitor()
            throws Exception {
        // Assume the model passed the validation.
        doReturn(new HashSet<>()).when(validator).validate(any());
        // Emulate the Pool-HealthMonitorMapping violation.
        doThrow(new MappingStatusException()).when(dataClient)
                .poolUpdate(any(
                        org.midonet.cluster.data.l4lb.Pool.class));

        // PUT the pool during its mappingStatus is PENDING_*, which triggers
        // 503 Service Unavailable.
        HealthMonitor anotherHealthMonitor = getStockHealthMonitor();
        pool.setHealthMonitorId(anotherHealthMonitor.getId());
        poolResource.update(pool.getId(), pool);
    }
}
