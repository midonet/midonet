/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.resources.AdRouteResource.BgpAdRouteResource;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for bgps.
 */
@RequestScoped
public class BgpResource {

    private final static Logger log = LoggerFactory
            .getLogger(BgpResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final BgpDao dao;
    private final ResourceFactory factory;

    @Inject
    public BgpResource(UriInfo uriInfo, SecurityContext context,
                       Authorizer authorizer, BgpDao dao,
                       ResourceFactory factory) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.dao = dao;
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

        if (!authorizer.bgpAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this BGP.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
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

        if (!authorizer.bgpAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this BGP.");
        }

        Bgp bgp = dao.get(id);
        if (bgp == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        bgp.setBaseUri(uriInfo.getBaseUri());

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
    public static class PortBgpResource {

        private final UUID portId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final BgpDao dao;

        @Inject
        public PortBgpResource(UriInfo uriInfo,
                               SecurityContext context,
                               Authorizer authorizer,
                               BgpDao dao,
                               @Assisted UUID portId) {
            this.portId = portId;
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.dao = dao;
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

            if (!authorizer.portAuthorized(context, AuthAction.WRITE, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add BGP to this port.");
            }

            bgp.setPortId(portId);
            UUID id = dao.create(bgp);
            return Response.created(
                    ResourceUriBuilder.getBgp(uriInfo.getBaseUri(), id))
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

            if (!authorizer.portAuthorized(context, AuthAction.READ, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these BGPs.");
            }

            List<Bgp> bgps = dao.findByPort(portId);
            if (bgps != null) {
                for (UriResource resource : bgps) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return bgps;
        }
    }
}
