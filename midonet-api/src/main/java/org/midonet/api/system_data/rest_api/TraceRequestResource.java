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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.validation.groups.Default;
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

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.TraceRequest;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;
import static org.midonet.cluster.rest_api.conversion.TraceRequestDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.TraceRequestDataConverter.toData;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Root Resource class for trace requests
 */
@RequestScoped
public class TraceRequestResource extends AbstractResource {

    private final static Logger log = getLogger(TraceRequestResource.class);

    @Inject
    public TraceRequestResource(RestApiConfig config, UriInfo uriInfo,
                                SecurityContext context, Validator validator,
                                DataClient dataClient) {
        super(config, uriInfo, context, dataClient, validator);
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
        List<TraceRequest> traceRequests = new ArrayList<>();
        for (org.midonet.cluster.data.TraceRequest data : traceRequestsData) {
            traceRequests.add(fromData(data, getBaseUri()));
        }
        return traceRequests;
    }

    private void tryAuthorize(org.midonet.cluster.data.TraceRequest tr,
                              String what) throws StateAccessException,
                                                  SerializationException{
        switch (tr.getDeviceType()) {
            case ROUTER:
                authoriser.tryAuthoriseRouter(tr.getDeviceId(), what);
                break;
            case BRIDGE:
                authoriser.tryAuthoriseBridge(tr.getDeviceId(), what);
                break;
            case PORT:
                authoriser.tryAuthorisePort(tr.getDeviceId(), what);
                break;
            default: throw new ForbiddenHttpException("Not authorized");
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
        tryAuthorize(traceRequest, "delete this trace request");
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

        tryAuthorize(traceRequestData, "view this trace request");

        return fromData(traceRequestData, getBaseUri());
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
            = toData(traceRequest);

        tryAuthorize(traceRequestData, "add a trace to this device.");

        UUID id = dataClient.traceRequestCreate(traceRequestData,
                                                traceRequest.enabled);
        return Response.created(
                ResourceUriBuilder.getTraceRequest(getBaseUri(), id)).build();
    }

    /**
     * Update the enabled status of the resource
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_TRACE_REQUEST_JSON,
                MediaType.APPLICATION_JSON })
    public void enableOrDisable(@PathParam("id") UUID id,
                                TraceRequest newTraceRequest)
            throws StateAccessException, SerializationException,
            RuleIndexOutOfBoundsException {
        org.midonet.cluster.data.TraceRequest traceRequest
            = dataClient.traceRequestGet(id);

        if (traceRequest == null) {
            throw new NotFoundHttpException("Trace request doesn't exist");
        }
        tryAuthorize(traceRequest, "enable a trace to this device");

        org.midonet.cluster.data.TraceRequest newData = toData(newTraceRequest);
        if (Objects.equals(newData.getName(), traceRequest.getName())
            && Objects.equals(newData.getDeviceType(),
                              traceRequest.getDeviceType())
            && Objects.equals(newData.getDeviceId(),
                              traceRequest.getDeviceId())
            && Objects.equals(newData.getCondition(),
                              traceRequest.getCondition())) {
            if (newTraceRequest.enabled) {
                dataClient.traceRequestEnable(id);
            } else {
                dataClient.traceRequestDisable(id);
            }
        } else {
            log.debug("Conflict between old {} and new {}",
                      traceRequest, newTraceRequest);
            throw new ConflictHttpException("Trace request mismatch. "
                    + "Only 'enabled' can be updated");
        }
    }
}
