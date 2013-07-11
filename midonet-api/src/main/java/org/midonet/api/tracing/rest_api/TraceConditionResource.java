/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.tracing.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.tracing.TraceCondition;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

@RequestScoped
public class TraceConditionResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(TraceConditionResource.class);

    private final DataClient dataClient;
    private final Validator validator;

    @Inject
    public TraceConditionResource(RestApiConfig config, UriInfo uriInfo,
                                  SecurityContext context,
                                  DataClient dataClient,
                                  Validator validator)
    {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
        this.validator = validator;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_CONDITION_COLLECTION_JSON,
                MediaType.APPLICATION_JSON})
    public List<TraceCondition> list()
        throws StateAccessException, SerializationException
    {
        List<org.midonet.cluster.data.TraceCondition> traceConditionDataList =
            dataClient.traceConditionsGetAll();
        List<TraceCondition> traceConditions = new ArrayList<TraceCondition>();
        for (org.midonet.cluster.data.TraceCondition traceConditionData :
                 traceConditionDataList)
        {
            TraceCondition traceCondition =
                new TraceCondition(traceConditionData);
            traceCondition.setBaseUri(getBaseUri());
            traceConditions.add(traceCondition);
        }
        return traceConditions;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_CONDITION_JSON,
                MediaType.APPLICATION_JSON})
    public TraceCondition get(@PathParam("id") UUID id)
        throws StateAccessException, SerializationException
    {
        if (!dataClient.traceConditionExists(id)) {
            throw new NotFoundHttpException();
        }

        org.midonet.cluster.data.TraceCondition traceConditionData =
            dataClient.traceConditionGet(id);
        TraceCondition traceCondition = new TraceCondition(traceConditionData);
        traceCondition.setBaseUri(getBaseUri());

        return traceCondition;
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
        throws StateAccessException, SerializationException
    {
        org.midonet.cluster.data.TraceCondition traceConditionData =
            dataClient.traceConditionGet(id);
        if (traceConditionData == null) {
            return;
        }

        dataClient.traceConditionDelete(id);
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_CONDITION_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(TraceCondition traceCondition)
        throws StateAccessException, SerializationException
    {
        Set<ConstraintViolation<TraceCondition>> violations =
            validator.validate(traceCondition);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        UUID id = dataClient.traceConditionCreate(traceCondition.toData());
        return Response.created(ResourceUriBuilder.
                                getTraceCondition(getBaseUri(), id)).build();
    }
}
