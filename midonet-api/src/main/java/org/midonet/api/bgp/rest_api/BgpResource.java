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

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.bgp.Bgp;
import org.midonet.api.bgp.rest_api.AdRouteResource.BgpAdRouteResource;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.event.topology.BgpEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

/**
 * Root resource class for bgps.
 */
@RequestScoped
public class BgpResource extends AbstractResource {

    private final static BgpEvent bgpEvent = new BgpEvent();

    private final ResourceFactory factory;

    @Inject
    public BgpResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, DataClient dataClient,
                       ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, null);
        this.factory = factory;
    }

    /**
     * Handler to deleting BGP.
     *
     * @param id BGP ID from the request.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) throws StateAccessException,
                                                        SerializationException {
        BGP bgpData = dataClient.bgpGet(id);
        if (bgpData == null) {
            return;
        }
        authoriser.tryAuthoriseBgp(id, "delete this BGP");
        dataClient.bgpDelete(id);
        bgpEvent.delete(id, bgpData);
    }

    /**
     * Handler to getting BGP.
     *
     * @param id BGP ID from the request.
     * @throws StateAccessException Data access error.
     * @return A BGP object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BGP_JSON,
            MediaType.APPLICATION_JSON })
    public Bgp get(@PathParam("id") UUID id) throws StateAccessException,
                                                    SerializationException {

        authoriser.tryAuthoriseBgp(id, "view this BGP.");

        BGP bgpData = dataClient.bgpGet(id);
        if (bgpData == null) {
            throw notFoundException(id, "bgp");
        }

        // Convert to the REST API DTO
        Bgp bgp = new Bgp(bgpData);
        bgp.setBaseUri(getBaseUri());

        return bgp;
    }

    /**
     * Advertising route resource locator for chains.
     *
     * @param id BGP ID from the request.
     * @return BgpAdRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.AD_ROUTES)
    public BgpAdRouteResource getBgpAdRouteResource(@PathParam("id") UUID id) {
        return factory.getBgpAdRouteResource(id);
    }

    /**
     * Sub-resource class for port's BGP.
     */
    @RequestScoped
    public static class PortBgpResource extends AbstractResource {

        private final UUID portId;

        @Inject
        public PortBgpResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               DataClient dataClient,
                               @Assisted UUID portId) {
            super(config, uriInfo, context, dataClient, null);
            this.portId = portId;
        }

        /**
         * Handler for creating BGP.
         *
         * @param bgp BGP object.
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_BGP_JSON,
                    MediaType.APPLICATION_JSON })
        public Response create(Bgp bgp) throws StateAccessException,
                                               SerializationException {

            bgp.setPortId(portId);

            authoriser.tryAuthorisePort(portId, "add BGP to this port.");

            UUID id = dataClient.bgpCreate(bgp.toData());
            bgpEvent.create(id, dataClient.bgpGet(id));
            return Response.created(
                    ResourceUriBuilder.getBgp(getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to getting a list of BGPs.
         *
         * @throws StateAccessException Data access error.
         * @return A list of BGP objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_BGP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bgp> list() throws StateAccessException,
                                       SerializationException {

            authoriser.tryAuthorisePort(portId, "view these BGPs.");

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
