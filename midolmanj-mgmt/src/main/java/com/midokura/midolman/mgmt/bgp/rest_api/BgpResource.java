/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.bgp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.bgp.Bgp;
import com.midokura.midolman.mgmt.bgp.auth.BgpAuthorizer;
import com.midokura.midolman.mgmt.bgp.rest_api.AdRouteResource.BgpAdRouteResource;
import com.midokura.midolman.mgmt.network.auth.PortAuthorizer;
import com.midokura.midolman.mgmt.rest_api.AbstractResource;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.ResourceFactory;
import com.midokura.midolman.mgmt.rest_api.RestApiConfig;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.BGP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for bgps.
 */
@RequestScoped
public class BgpResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(BgpResource.class);

    private final Authorizer authorizer;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public BgpResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, BgpAuthorizer authorizer,
                       DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.dataClient = dataClient;
        this.factory = factory;
    }

    /**
     * Handler to deleting BGP.
     *
     * @param id
     *            BGP ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        BGP bgpData = dataClient.bgpGet(id);
        if (bgpData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this BGP.");
        }

        dataClient.bgpDelete(id);
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
    public Bgp get(@PathParam("id") UUID id) throws StateAccessException {

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

    /**
     * Advertising route resource locator for chains.
     *
     * @param id
     *            BGP ID from the request.
     * @returns BgpAdRouteResource object to handle sub-resource requests.
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
        private final SecurityContext context;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public PortBgpResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               PortAuthorizer authorizer,
                               DataClient dataClient,
                               @Assisted UUID portId) {
            super(config, uriInfo, context);
            this.portId = portId;
            this.context = context;
            this.authorizer = authorizer;
            this.dataClient = dataClient;
        }

        /**
         * Handler for creating BGP.
         *
         * @param bgp
         *            BGP object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_BGP_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Bgp bgp)
                throws StateAccessException, InvalidStateOperationException {

            bgp.setPortId(portId);

            if (!authorizer.authorize(context, AuthAction.WRITE, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add BGP to this port.");
            }

            UUID id = dataClient.bgpCreate(bgp.toData());
            return Response.created(
                    ResourceUriBuilder.getBgp(getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to getting a list of BGPs.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of BGP objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_BGP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Bgp> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these BGPs.");
            }

            List<BGP> bgpDataList = dataClient.bgpFindByPort(portId);
            List<Bgp> bgpList = new ArrayList<Bgp>();
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
