/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.api.system_data.rest_api;


import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.validation.groups.Default;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.auth.MockAuthConfig;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.network.auth.PortAuthorizer;
import org.midonet.api.network.auth.RouterAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.system_data.TraceRequest;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;

/**
 * Root Resource class for trace requests
 */
@RequestScoped
public class TraceRequestResource extends AbstractResource {
    private final static Logger log
        = LoggerFactory.getLogger(TraceRequestResource.class);

    private final BridgeAuthorizer bridgeAuthorizer;
    private final PortAuthorizer portAuthorizer;
    private final RouterAuthorizer routerAuthorizer;

    @Inject
    public TraceRequestResource(RestApiConfig config, UriInfo uriInfo,
                                SecurityContext context,
                                BridgeAuthorizer bridgeAuthorizer,
                                PortAuthorizer portAuthorizer,
                                RouterAuthorizer routerAuthorizer,
            Validator validator, DataClient dataClient,
                                MockAuthConfig authconfig) {
        super(config, uriInfo, context, dataClient, validator);
        this.bridgeAuthorizer = bridgeAuthorizer;
        this.portAuthorizer = portAuthorizer;
        this.routerAuthorizer = routerAuthorizer;
    }

    /**
     * Handler for getting the list of traces
     *
     * @return The system state info
     * @throws StateAccessException
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Produces({ VendorMediaType.APPLICATION_TRACE_REQUEST_COLLECTION_JSON,
                MediaType.APPLICATION_JSON})
    public List<TraceRequest> list()
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.TraceRequest> traceRequestsData
            = Collections.emptyList();

        String tenantId = context.getUserPrincipal().getName();
        if (context.isUserInRole(AuthRole.ADMIN)) {
            traceRequestsData = dataClient.traceRequestGetAll();
        } else {
            traceRequestsData = dataClient.traceRequestFindByTenant(tenantId);
        }
        List<TraceRequest> traceRequests = new ArrayList<TraceRequest>();
        for (org.midonet.cluster.data.TraceRequest data : traceRequestsData) {
            TraceRequest t = new TraceRequest(data);
            t.setBaseUri(getBaseUri());
            traceRequests.add(t);
        }
        return traceRequests;
    }

    private static class ForbiddenAuthorizer extends Authorizer<UUID> {
        @Override
        public boolean authorize(SecurityContext context,
                                 AuthAction action, UUID id)
                throws StateAccessException, SerializationException {
            return false;
        }
    }

    private Authorizer<UUID> getAuthorizer(
            org.midonet.cluster.data.TraceRequest.DeviceType type) {
        switch (type) {
        case ROUTER:
            return routerAuthorizer;
        case BRIDGE:
            return bridgeAuthorizer;
        case PORT:
            return portAuthorizer;
        default:
            return new ForbiddenAuthorizer();
        }
    }

    /**
     * Handler for deleting a trace request
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.TraceRequest traceRequest
            = dataClient.traceRequestGet(id);
        if (traceRequest == null) {
            return;
        }

        Authorizer<UUID> authorizer
            = getAuthorizer(traceRequest.getDeviceType());
        if (!authorizer.authorize(context, AuthAction.WRITE,
                                  traceRequest.getDeviceId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this trace request.");
        }

        dataClient.traceRequestDelete(id);
    }

    /**
     * Handler to get a specific trace request
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_TRACE_REQUEST_JSON,
                MediaType.APPLICATION_JSON })
    public TraceRequest get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.TraceRequest traceRequestData
            = dataClient.traceRequestGet(id);
        if (traceRequestData == null) {
            throw new NotFoundHttpException("Trace request doesn't exist");
        }

        Authorizer<UUID> authorizer = getAuthorizer(
                traceRequestData.getDeviceType());
        if (!authorizer.authorize(context, AuthAction.READ,
                                  traceRequestData.getDeviceId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this trace request.");
        }

        TraceRequest traceRequest = new TraceRequest(traceRequestData);
        traceRequest.setBaseUri(getBaseUri());

        return traceRequest;
    }

    /**
     * Handler for creating a trace request
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TRACE_REQUEST_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(TraceRequest traceRequest)
            throws StateAccessException, SerializationException,
            RuleIndexOutOfBoundsException {
        validate(traceRequest, Default.class);

        org.midonet.cluster.data.TraceRequest traceRequestData
            = traceRequest.toData();
        Authorizer<UUID> authorizer = getAuthorizer(
                traceRequestData.getDeviceType());
        if (!authorizer.authorize(context, AuthAction.WRITE,
                                  traceRequest.getDeviceId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add a trace to this device.");
        }

        UUID id = dataClient.traceRequestCreate(traceRequestData);
        return Response.created(
                ResourceUriBuilder.getTraceRequest(getBaseUri(), id)).build();
    }

}
