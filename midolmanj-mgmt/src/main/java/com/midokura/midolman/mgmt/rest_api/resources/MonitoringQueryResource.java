/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import javax.annotation.security.PermitAll;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.dto.MetricQuery;
import com.midokura.midolman.mgmt.data.dto.MetricQueryResponse;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.state.StateAccessException;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 5/3/12
 */
public class MonitoringQueryResource {

    private final static Logger log = LoggerFactory
            .getLogger(MonitoringQueryResource.class);


    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_MONITORING_RESOURCE_JSON,
                      MediaType.APPLICATION_JSON })
    public MetricQueryResponse get(@Context SecurityContext context,
                                    @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                                    @Context Authorizer authorizer) throws StateAccessException {

        MetricQueryResponse res = new MetricQueryResponse();
        return res;
    }

    @POST
    @Path("{query}")
    @PermitAll
    @Consumes({VendorMediaType.APPLICATION_MONITORING_QUERY_JSON,
                      MediaType.APPLICATION_JSON})
    @Produces({ VendorMediaType.APPLICATION_MONITORING_RESOURCE_JSON,
                      MediaType.APPLICATION_JSON })
    public MetricQueryResponse post(MetricQuery query, @Context SecurityContext context,
                    @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                    @Context Authorizer authorizer) throws StateAccessException {

        MetricDao dao = daoFactory.getMetricDao();
        return dao.executeQuery(query);
    }
}
