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
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.RouterDataConverter;
import org.midonet.cluster.rest_api.models.Router;
import org.midonet.event.topology.RouterEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

@RequestScoped
public class RouterResource extends AbstractResource {

    private final ResourceFactory factory;
    private final RouterEvent routerEvent = new RouterEvent() ;

    @Inject
    public RouterResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context,  Validator validator,
                          DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    /**
     * Handler to deleting a router.
     *
     * @param id Router ID from the request.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) throws StateAccessException,
                                                        SerializationException {
        org.midonet.cluster.data.Router routerData =
            authoriser.tryAuthoriseRouter(id, "delete this router");
        if (routerData == null) {
            return;
        }
        dataClient.routersDelete(id);
        routerEvent.delete(id);
    }

    /**
     * Handler to getting a router.
     *
     * @param id Router ID from the request.
     * @throws StateAccessException Data access error.
     * @return A Router object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_JSON,
                VendorMediaType.APPLICATION_ROUTER_JSON_V2,
                MediaType.APPLICATION_JSON })
    public Router get(@PathParam("id") UUID id) throws StateAccessException,
                                                       SerializationException {

        org.midonet.cluster.data.Router routerData =
            authoriser.tryAuthoriseRouter(id, "view this router");

        if (routerData == null) {
            throw notFoundException(id, "router");
        }

        return RouterDataConverter.fromData(routerData, getBaseUri());
    }

    /**
     * Port resource locator for routers.
     *
     * @param id Router ID from the request.
     * @return RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public PortResource.RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return factory.getRouterPortResource(id);
    }

    /**
     * Route resource locator for routers.
     *
     * @param id Router ID from the request.
     * @return RouterRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTES)
    public RouteResource.RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return factory.getRouterRouteResource(id);
    }

    /**
     * Peer port resource locator for bridges.
     *
     * @param id Router ID from the request.
     * @return RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PEER_PORTS)
    public PortResource.RouterPeerPortResource getRouterPeerPortResource(
            @PathParam("id") UUID id) {
        return factory.getRouterPeerPortResource(id);
    }

    /**
     * Handler to updating a router.
     *
     * @param id Router ID from the request.
     * @param router Router object.
     * @throws StateAccessException Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
                VendorMediaType.APPLICATION_ROUTER_JSON_V2,
                MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Router router)
            throws StateAccessException, SerializationException {

        router.id = id;

        validate(router);

        authoriser.tryAuthoriseRouter(id, "update this router");

        dataClient.routersUpdate(RouterDataConverter.toData(router));
        routerEvent.update(id, dataClient.routersGet(id));
    }

    /**
     * Handler for creating a tenant router.
     *
     * @param router Router object.
     * @throws StateAccessException Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
                VendorMediaType.APPLICATION_ROUTER_JSON_V2,
                MediaType.APPLICATION_JSON })
    public Response create(Router router) throws StateAccessException,
                                                 SerializationException {

        validate(router);

        if (!authoriser.isAdminOrOwner(router.tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add router to this tenant.");
        }

        UUID id = dataClient.routersCreate(RouterDataConverter.toData(router));

        routerEvent.create(id, dataClient.routersGet(id).toString());

        return Response.created(ResourceUriBuilder.getRouter(getBaseUri(), id))
                       .build();
    }

    /**
     * Handler to list all routers.
     *
     * @throws StateAccessException Data access error.
     * @return A list of Router objects.
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON_V2,
                MediaType.APPLICATION_JSON })
    public List<Router> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.Router> dataRouters = (tenantId == null) ?
                dataClient.routersGetAll() :
                dataClient.routersFindByTenant(tenantId);

        List<Router> routers = new ArrayList<>();
        if (dataRouters != null) {
            for (org.midonet.cluster.data.Router dataRouter :
                    dataRouters) {
                routers.add(RouterDataConverter.fromData(dataRouter, getBaseUri()));
            }
        }
        return routers;
    }
}
