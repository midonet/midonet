/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.monitoring.rest_api;

import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.monitoring.Metric;
import com.midokura.midolman.mgmt.monitoring.MetricQuery;
import com.midokura.midolman.mgmt.monitoring.MetricQueryResponse;
import com.midokura.midolman.mgmt.monitoring.MetricTarget;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Date: 5/3/12
 */
@RequestScoped
public class MonitoringResource {

    private final static Logger log = LoggerFactory
            .getLogger(MonitoringResource.class);

    private final DataClient dataClient;

    @Inject
    public MonitoringResource(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    /**
     * @param query to execute
     * @return the results of the query
     */
    MetricQueryResponse executeQuery(MetricQuery query) {
        Map<String, Long> results = new HashMap<String, Long>();
        results = dataClient.metricsGetTSPoints(query.getType(),
                query.getTargetIdentifier().toString(),
                query.getMetricName(),
                query.getTimeStampStart(),
                query.getTimeStampEnd()

        );
        MetricQueryResponse response = new MetricQueryResponse();
        response.setMetricName(query.getMetricName());
        response.setTargetIdentifier(query.getTargetIdentifier());
        response.setType(query.getType());
        response.setResults(results);
        response.setTimeStampStart(query.getTimeStampStart());
        response.setTimeStampEnd(query.getTimeStampEnd());
        return response;
    }


    /**
     * This method will execute the query against the collected metrics data
     * store and return the results.
     *
     * @param queries the queries that the system will process
     * @return MetricQueryResponse with the results of the query
     * @throws StateAccessException
     */
    @POST
    @Path("/query")
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_MONITORING_QUERY_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    @Produces({VendorMediaType
            .APPLICATION_MONITORING_QUERY_RESPONSE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<MetricQueryResponse> post(List<MetricQuery> queries)
        throws StateAccessException {

        List<MetricQueryResponse> results =
                new ArrayList<MetricQueryResponse>();
        for (MetricQuery query : queries) {
            results.add(executeQuery(query));
        }
        return results;
    }

    /**
     * This method gets all the metrics associated with an object from the data
     * store.
     *
     * @param target     we want to get all the metric for this object
     * @return List of Metric for this target
     * @throws StateAccessException
     */
    @POST
    @Path("/filter")
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes(
        {VendorMediaType.APPLICATION_METRIC_TARGET_JSON,
                MediaType.APPLICATION_JSON})
    @Produces({VendorMediaType.APPLICATION_METRICS_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Metric> getMetricResource(MetricTarget target)
        throws StateAccessException {

        List<String> metricsTypes = dataClient.metricsGetTypeForTarget(
                target.getTargetIdentifier().toString());
        List<Metric> result = new ArrayList<Metric>();
        for(String type : metricsTypes){
            List<String> metrics = dataClient.metricsGetForType(type);
            for (String m : metrics) {
                Metric aMetric = new Metric();
                aMetric.setTargetIdentifier(target.getTargetIdentifier());
                aMetric.setName(m);
                aMetric.setType(type);
                result.add(aMetric);
            }
        }
        return result;
    }


}
