/*
 * Copyright (c) 2013 Midokura Pte.Ltd.
 */

package org.midonet.api.tracing.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.tracing.Trace;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Root Resource class for Packet Trace Data
 */
@RequestScoped
public class TraceResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(TraceResource.class);

    private final DataClient dataClient;

    @Inject
    public TraceResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, DataClient dataClient) {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
    }

    /**
     * This method will dump the Trace IDs
     *
     * @return List<Trace> - a list of Trace IDs and
     *                      corresponding number of messages per trace
     * @throws StateAccessException
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_TRACE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Trace> getTraceIds()
        throws StateAccessException {
        List<Trace> retList = new ArrayList<Trace>();
        Map<String, String> retMap = dataClient.traceIdList(0);

        if (retMap.isEmpty() == false) {
            Iterator<String> it = retMap.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                String value = retMap.get(key);
                Trace trace = new Trace();
                trace.setTraceId(UUID.fromString(key));
                trace.setNumTraceMessages(Integer.parseInt(value));

                retList.add(trace);
            }
        }
        return(retList);
    }

    /**
     * This method gets trace messages given a trace ID
     *
     * @param traceId - the trace ID to retrieve trace messages for
     * @return List of trace messages associated with the given trace ID
     * @throws StateAccessException
     */
    @GET
    @Path("{id}")
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_JSON,
                  MediaType.APPLICATION_JSON})
    public Trace getTraceMessages(UUID traceId)
        throws StateAccessException {

        Trace trace = new Trace();

        trace.setTraceId(traceId);
        trace.setTraceMessages(dataClient.packetTraceGet(traceId));

        return trace;
    }

    /**
     * This method deletes all trace messages associated with a given
     * trace Id
     *
     * @param traceId - the trace ID to retrieve trace messages for
     * @throws StateAccessException
     */
    @DELETE
    @Path("{id}")
    @RolesAllowed({AuthRole.ADMIN})
    public void deleteTrace(UUID traceId)
        throws StateAccessException {

        dataClient.packetTraceDelete(traceId);
        dataClient.traceIdDelete(traceId);
    }
}
