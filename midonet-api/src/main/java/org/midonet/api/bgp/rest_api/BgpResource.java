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
package org.midonet.api.bgp.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
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

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.bgp.Bgp;
import org.midonet.api.bgp.auth.BgpAuthorizer;
import org.midonet.api.bgp.rest_api.AdRouteResource.BgpAdRouteResource;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.VendorMediaType;
import org.midonet.brain.services.rest_api.auth.AuthAction;
import org.midonet.brain.services.rest_api.auth.AuthRole;
import org.midonet.brain.services.rest_api.auth.ForbiddenHttpException;
import org.midonet.brain.services.rest_api.network.auth.PortAuthorizer;
import org.midonet.brain.services.rest_api.rest_api.NotFoundHttpException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.BGP;
import org.midonet.event.topology.BgpEvent;
import org.midonet.util.serialization.SerializationException;

@RequestScoped
public class BgpResource extends AbstractResource {

    private final static BgpEvent bgpEvent = new BgpEvent();

    private final BgpAuthorizer authorizer;
    private final ResourceFactory factory;

    @Inject
    public BgpResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, BgpAuthorizer authorizer,
                       DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context, dataClient);
        this.authorizer = authorizer;
        this.factory = factory;
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        BGP bgpData = dataClient.bgpGet(id);
        if (bgpData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this BGP.");
        }

        dataClient.bgpDelete(id);
        bgpEvent.delete(id, bgpData);
    }

    /**
     * Handler to getting BGP.
     *
     * @param id
     *            BGP ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A BGP object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BGP_JSON,
            MediaType.APPLICATION_JSON })
    public Bgp get(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {
        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this BGP.");
        }

        BGP bgpData = dataClient.bgpGet(id);
        if (bgpData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Bgp bgp = new Bgp(bgpData);
        bgp.setBaseUri(getBaseUri());

        return bgp;
    }

    @Path("/{id}" + ResourceUriBuilder.AD_ROUTES)
    public BgpAdRouteResource getBgpAdRouteResource(@PathParam("id") UUID id) {
        return factory.getBgpAdRouteResource(id);
    }

    @RequestScoped
    public static class PortBgpResource extends AbstractResource {

        private final UUID portId;
        private final SecurityContext context;
        private final PortAuthorizer authorizer;

        @Inject
        public PortBgpResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               PortAuthorizer authorizer,
                               DataClient dataClient,
                               @Assisted UUID portId) {
            super(config, uriInfo, context, dataClient);
            this.portId = portId;
            this.context = context;
            this.authorizer = authorizer;
        }

        /**
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_BGP_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Bgp bgp)
                throws StateAccessException,
                SerializationException {

            bgp.setPortId(portId);

            if (!authorizer.authorize(context, AuthAction.WRITE, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add BGP to this port.");
            }

            UUID id = dataClient.bgpCreate(bgp.toData());
            bgpEvent.create(id, dataClient.bgpGet(id));
            return Response.created(
                    ResourceUriBuilder.getBgp(getBaseUri(), id))
                    .build();
        }

        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_BGP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bgp> list() throws StateAccessException,
                SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these BGPs.");
            }

            List<BGP> bgpDataList = dataClient.bgpFindByPort(portId);
            List<Bgp> bgpList = new ArrayList<>();
            if (bgpDataList != null) {
                for (BGP bgpData : bgpDataList) {
                    Bgp bgp = new Bgp(bgpData);
                    bgp.setBaseUri(getBaseUri());
                    bgpList.add(bgp);
                }
            }
            return bgpList;
        }
    }
}
