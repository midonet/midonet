/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.api.network.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.PortGroup;
import org.midonet.cluster.rest_api.models.PortGroupPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.conversion.PortGroupDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.PortGroupDataConverter.toData;

/**
 * Root resource class for port groups.
 */
@RequestScoped
public class PortGroupResource extends AbstractResource {

    private final ResourceFactory factory;

    @Inject
    public PortGroupResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context, Validator validator,
                             DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    /**
     * Handler to deleting a port group.
     *
     * @param id PortGroup ID from the request.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) throws StateAccessException,
                                                        SerializationException {

        org.midonet.cluster.data.PortGroup pg = dataClient.portGroupsGet(id);
        if (pg == null) {
            return;
        }
        authoriser.tryAuthorisePortGroup(id, "delete this port group");
        dataClient.portGroupsDelete(id);
    }

    /**
     * Handler to getting a port group.
     *
     * @param id PortGroup ID from the request.
     * @throws StateAccessException Data access error.
     * @return A PortGroup object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_JSON })
    public PortGroup get(@PathParam("id") UUID id) throws Exception {

        org.midonet.cluster.data.PortGroup pg =
            authoriser.tryAuthorisePortGroup(id, "view this port group");
        if (pg == null) {
            throw notFoundException(id, "port group");
        }

        return fromData(pg, getBaseUri());
    }

    /**
     * Handler for creating a tenant port group.
     *
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_PORTGROUP_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(PortGroup group)
            throws StateAccessException, SerializationException {

        validate(group);

        if (!authoriser.isAdminOrOwner(group.tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add PortGroup to this tenant.");
        }

        UUID id = dataClient.portGroupsCreate(toData(group));
        return Response.created(
            ResourceUriBuilder.getPortGroup(getBaseUri(), id)).build();
    }

    /**
     * Handler to getting a collection of PortGroups.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
    public List<PortGroup> list(@QueryParam("tenant_id") String tenantId,
                                @QueryParam("port_id") UUID portId)
        throws Exception {

        List<org.midonet.cluster.data.PortGroup> list;
        if (portId != null) {
            list = dataClient.portGroupsFindByPort(portId);
        } else if (tenantId != null) {
            list = dataClient.portGroupsFindByTenant(tenantId);
        } else {
            list = dataClient.portGroupsGetAll();
        }

        List<PortGroup> portGroups = new ArrayList<>();
        if (list != null) {
            for (org.midonet.cluster.data.PortGroup data : list) {
                PortGroup portGroup = fromData(data, getBaseUri());
                portGroup.setBaseUri(getBaseUri());
                portGroups.add(portGroup);
            }
        }
        return portGroups;
    }

    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public PortResource.PortGroupPortResource getPortGroupPortResource(
            @PathParam("id") UUID id) {
        return factory.getPortGroupPortResource(id);
    }

    /**
     * Sub-resource class for port's port groups.
     */
    @RequestScoped
    public static class PortPortGroupResource extends AbstractResource {

        private final UUID portId;

        @Inject
        public PortPortGroupResource(RestApiConfig config,
                                     UriInfo uriInfo,
                                     SecurityContext context,
                                     DataClient dataClient,
                                     @Assisted UUID portId) {
            super(config, uriInfo, context, dataClient, null);
            this.portId = portId;
        }

        /**
         * Handler to list port's PortGroups.
         *
         * @throws StateAccessException Data access error.
         * @return A list of PortGroupPort objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_PORT_COLLECTION_JSON })
        public List<PortGroupPort> list() throws Exception {

            List<org.midonet.cluster.data.PortGroup> list =
                fetchPortGroupsHelper();

            List<PortGroupPort> portGroups = new ArrayList<>();
            if (list != null) {
                for (org.midonet.cluster.data.PortGroup portGroupData : list) {
                    PortGroupPort portGroup = new PortGroupPort();
                    portGroup.portGroupId = portGroupData.getId();
                    portGroup.portId = portId;
                    portGroup.setBaseUri(getBaseUri());
                    portGroups.add(portGroup);
                }
            }
            return portGroups;
        }

        /** Deprecated version of list() - uses a different mediatype
         *  @return List<PortGroup>
         */
        @GET
        @PermitAll
        @Deprecated
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON })
        public List<PortGroup> listV1() throws Exception {

            List<org.midonet.cluster.data.PortGroup> list =
                fetchPortGroupsHelper();

            List<PortGroup> portGroups = new ArrayList<>();
            if (list != null) {
                for (org.midonet.cluster.data.PortGroup data : list) {
                    portGroups.add(fromData(data, getBaseUri()));
                }
            }
            return portGroups;
        }

        private List<org.midonet.cluster.data.PortGroup> fetchPortGroupsHelper()
            throws StateAccessException, SerializationException {
            authoriser.tryAuthorisePort(portId, "view port groups for port");
            return dataClient.portGroupsFindByPort(portId);
        }
    }
}
