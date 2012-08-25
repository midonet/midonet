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
import com.midokura.midolman.mgmt.auth.authorizer.Authorizer;
import com.midokura.midolman.mgmt.auth.authorizer.BridgeAuthorizer;
import com.midokura.midolman.mgmt.auth.authorizer.PortAuthorizer;
import com.midokura.midolman.mgmt.auth.authorizer.RouterAuthorizer;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortInUseException;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.resources.BgpResource.PortBgpResource;
import com.midokura.midolman.mgmt.rest_api.resources.VpnResource.PortVpnResource;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for ports.
 */
@RequestScoped
public class PortResource {

    private final static Logger log = LoggerFactory
            .getLogger(PortResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final Validator validator;
    private final PortDao dao;
    private final ResourceFactory factory;

    @Inject
    public PortResource(UriInfo uriInfo, SecurityContext context,
                        PortAuthorizer authorizer, Validator validator,
                        PortDao dao, ResourceFactory factory) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.validator = validator;
        this.dao = dao;
        this.factory = factory;
    }

    /**
     * Handler to deleting a port.
     *
     * @param id
     *            Port ID from the request.
  * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this port.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (PortInUseException e) {
            throw new BadRequestHttpException(e,
                    "Attempted to delete a port that is in use");
        }
    }

    /**
     * Handler to getting a port.
     *
     * @param id
     *            Port ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Port object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public Port get(@PathParam("id") UUID id) throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this port.");
        }

        Port port = dao.get(id);
        if (port == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        port.setBaseUri(uriInfo.getBaseUri());

        return port;
    }

    /**
     * Handler to updating a port.
     *
     * @param id
     *            Port ID from the request.
     * @param port
     *            Port object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Port port)
            throws StateAccessException, InvalidStateOperationException {

        port.setId(id);

        Set<ConstraintViolation<Port>> violations = validator.validate(port);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this port.");
        }
        dao.update(port);
    }

    /**
     * Handler to linking ports.
     *
     * @param id
     *            Port ID from the request.
     * @param input
     *            Input
     * @throws StateAccessException
     *             Data access error.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}/link")
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public void link(@PathParam("id") UUID id, JSONObject input)
            throws StateAccessException {

        // Make sure that peerPortId was explicitly specified.
        if (!input.containsKey("peerId")) {
            throw new BadRequestHttpException("PeerId is required.");
        }

        // Explicitly passed in null peerId indicates 'unlink'
        UUID peerId = null;
        Object peerIdElem = input.get("peerId");
        if (peerIdElem != null) {
            try {
                peerId = UUID.fromString(peerIdElem.toString());
            } catch (IllegalArgumentException ex) {
                // Invalid UUID passed in
                throw new BadRequestHttpException("PeerId is invalid.");
            }
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)
                || !authorizer
                        .authorize(context, AuthAction.WRITE, peerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to link these ports.");
        }

        if (peerId != null) {
            try {
                dao.link(id, peerId);
            } catch (PortInUseException e) {
                log.error("Attempted to link ports that are in use", e);
                throw new BadRequestHttpException("Invalid rule position.");
            }
        } else {
            dao.unlink(id);
        }
    }

    /**
     * Port resource locator for BGP.
     *
     * @param id
     *            Port ID from the request.
     * @returns PortBgpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BGP)
    public PortBgpResource getBgpResource(@PathParam("id") UUID id) {
        return factory.getPortBgpResource(id);
    }

    /**
     * Port resource locator for VPN.
     *
     * @param id
     *            Port ID from the request.
     * @returns PortVpnResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.VPN)
    public PortVpnResource getVpnResource(@PathParam("id") UUID id) {
        return factory.getPortVpnResource(id);
    }

    /**
     * Sub-resource class for bridge's ports.
     */
    @RequestScoped
    public static class BridgePortResource {

        private final UUID bridgeId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final Validator validator;
        private final PortDao dao;

        @Inject
        public BridgePortResource(UriInfo uriInfo,
                                  SecurityContext context,
                                  BridgeAuthorizer authorizer,
                                  Validator validator,
                                  PortDao dao,
                                  @Assisted UUID bridgeId) {
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.validator = validator;
            this.dao = dao;
            this.bridgeId = bridgeId;
        }

        /**
         * Handler to create a bridge port.
         *
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(BridgePort port)
                throws StateAccessException, InvalidStateOperationException {

            port.setDeviceId(bridgeId);

            Set<ConstraintViolation<BridgePort>> violations = validator
                    .validate(port);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add port to this bridge.");
            }

            UUID id = dao.create(port);
            return Response.created(
                    ResourceUriBuilder.getPort(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list bridge ports.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<Port> ports = dao.findByBridge(bridgeId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for bridge's peer ports.
     */
    @RequestScoped
    public static class BridgePeerPortResource {

        private final UUID bridgeId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final PortDao dao;

        @Inject
        public BridgePeerPortResource(UriInfo uriInfo,
                                      SecurityContext context,
                                      BridgeAuthorizer authorizer,
                                      PortDao dao,
                                      @Assisted UUID bridgeId) {
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.dao = dao;
            this.bridgeId = bridgeId;
        }

        /**
         * Handler to list bridge peer ports.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<Port> ports = dao.findPeersByBridge(bridgeId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for router's ports.
     */
    @RequestScoped
    public static class RouterPortResource {

        private final UUID routerId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final Validator validator;
        private final PortDao dao;

        @Inject
        public RouterPortResource(UriInfo uriInfo,
                                  SecurityContext context,
                                  RouterAuthorizer authorizer,
                                  Validator validator,
                                  PortDao dao,
                                  @Assisted UUID routerId) {
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.validator = validator;
            this.dao = dao;
            this.routerId = routerId;
        }

        /**
         * Handler to create a router port.
         *
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(RouterPort port)
                throws StateAccessException, InvalidStateOperationException {

            port.setDeviceId(routerId);

            Set<ConstraintViolation<RouterPort>> violations = validator
                    .validate(port);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.authorize(context, AuthAction.WRITE, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add port to this router.");
            }

            UUID id = dao.create(port);
            return Response.created(
                    ResourceUriBuilder.getPort(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list router ports.
         *
         *             Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<Port> ports = dao.findByRouter(routerId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for router peer ports.
     */
    @RequestScoped
    public static class RouterPeerPortResource {

        private final UUID routerId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final PortDao dao;

        @Inject
        public RouterPeerPortResource(UriInfo uriInfo,
                                      SecurityContext context,
                                      RouterAuthorizer authorizer,
                                      PortDao dao,
                                      @Assisted UUID routerId) {
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.dao = dao;
            this.routerId = routerId;
        }

        /**
         * Handler to list router peer ports.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<Port> ports = dao.findPeersByRouter(routerId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }
}
