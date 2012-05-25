/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.dto.Metric;
import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;
import com.midokura.midolman.mgmt.data.dto.MetricTarget;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.state.StateAccessException;

/**
 * Date: 5/3/12
 */
public class MonitoringResource {

    private final static Logger log = LoggerFactory
            .getLogger(MonitoringResource.class);

    @POST
    @Path("/query")
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_MONITORING_QUERY_JSON,
                      MediaType.APPLICATION_JSON})
    @Produces({VendorMediaType.APPLICATION_MONITORING_RESOURCE_JSON,
                      MediaType.APPLICATION_JSON})
    public MetricQueryResponse post(MetricQuery query,
                                    @Context SecurityContext context,
                                    @Context UriInfo uriInfo,
                                    @Context DaoFactory daoFactory,
                                    @Context Authorizer authorizer)
            throws StateAccessException {

        MetricDao dao = daoFactory.getMetricDao();
        return dao.executeQuery(query);
    }

    @POST
    @Path("/filter")
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes(
            {VendorMediaType.APPLICATION_METRIC_TARGET_JSON, MediaType.APPLICATION_JSON})
    @Produces({VendorMediaType.APPLICATION_METRICS_COLLECTION_JSON,
                      MediaType.APPLICATION_JSON})
    public List<Metric> getMetricResource(MetricTarget target,
                                          @Context SecurityContext context,
                                          @Context UriInfo uriInfo,
                                          @Context DaoFactory daoFactory,
                                          @Context Authorizer authorizer)
            throws StateAccessException {

        MetricDao dao = daoFactory.getMetricDao();
        return dao.listMetrics(target.getType(), target.getTargetUUID());
    }
}
