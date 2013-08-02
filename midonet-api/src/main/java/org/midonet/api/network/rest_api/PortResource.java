/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.apache.zookeeper.KeeperException;
import org.midonet.api.network.*;
import org.midonet.api.network.Port;
import org.midonet.api.rest_api.*;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.bgp.rest_api.BgpResource.PortBgpResource;
import org.midonet.api.network.PortGroupPort.PortGroupPortCreateGroupSequence;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.network.auth.PortAuthorizer;
import org.midonet.api.network.auth.PortGroupAuthorizer;
import org.midonet.api.network.auth.RouterAuthorizer;
import org.midonet.cluster.data.*;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.*;
import org.midonet.cluster.DataClient;
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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for ports.
 */
@RequestScoped
public class PortResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(PortResource.class);

    private final Authorizer authorizer;
    private final Validator validator;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public PortResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, PortAuthorizer authorizer,
                        Validator validator, DataClient dataClient,
                        ResourceFactory factory) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.validator = validator;
        this.dataClient = dataClient;
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
            throws StateAccessException,
            InvalidStateOperationException,
            SerializationException {

        // Get the port and validate that this can be deleted
        org.midonet.cluster.data.Port portData = dataClient.portsGet(id);
        if (portData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this port.");
        }

        Port port = PortFactory.createPort(portData);
        Set<ConstraintViolation<Port>> violations = validator.validate(port,
                Port.PortDeleteGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.portsDelete(id);
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
    public Port get(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this port.");
        }

        org.midonet.cluster.data.Port portData =
                dataClient.portsGet(id);
        if (portData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        Port port = PortFactory.createPort(portData);
        port.setBaseUri(getBaseUri());

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
            throws StateAccessException,
            InvalidStateOperationException,
            SerializationException {

        port.setId(id);

        Set<ConstraintViolation<Port>> violations = validator.validate(port);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this port.");
        }

        dataClient.portsUpdate(port.toData());
    }

    /**
     * Handler to linking ports.
     *
     * @param id
     *            Port ID from the request.
     * @param link
     *            Link object
     * @throws StateAccessException
     *             Data access error.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}/link")
    @Consumes({ VendorMediaType.APPLICATION_PORT_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public Response link(@PathParam("id") UUID id, Link link)
            throws StateAccessException,
            SerializationException {

        link.setPortId(id);

        Set<ConstraintViolation<Link>> violations = validator.validate(link,
                    Link.LinkCreateGroupSequence.class);

        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)
                || !authorizer
                        .authorize(context, AuthAction.WRITE,
                                link.getPeerId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to link these ports.");
        }

        dataClient.portsLink(link.getPortId(), link.getPeerId());

        return Response.created(
                ResourceUriBuilder.getPortLink(getBaseUri(), id))
                .build();
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}/link")
    public void unlink(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {

        // Idempotent operation: if the port does not exists, just return.
        org.midonet.cluster.data.Port portData =
                dataClient.portsGet(id);
        if (portData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to unlink these ports.");
        }

        dataClient.portsUnlink(id);
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
     * Port resource locator for port group.
     *
     * @param id
     *            Port ID from the request.
     * @returns PortPortGroupResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORT_GROUPS)
    public PortGroupResource.PortPortGroupResource getPortGroupResource(
            @PathParam("id") UUID id) {
        return factory.getPortPortGroupResource(id);
    }

    /**
     * Sub-resource class for bridge's ports.
     */
    @RequestScoped
    public static class BridgePortResource extends AbstractResource {

        private final UUID bridgeId;
        private final Authorizer authorizer;
        private final Validator validator;
        private final DataClient dataClient;

        @Inject
        public BridgePortResource(RestApiConfig config,
                                  UriInfo uriInfo,
                                  SecurityContext context,
                                  BridgeAuthorizer authorizer,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID bridgeId) {
            super(config, uriInfo, context);
            this.authorizer = authorizer;
            this.validator = validator;
            this.dataClient = dataClient;
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
                throws StateAccessException,
                InvalidStateOperationException,
                SerializationException {

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

            // If we are running on a pre-1.2 version, the VLANs path
            // in the bridge may not exist, so let's ensure it exists
            dataClient.ensureBridgeHasVlanDirectory(port.getDeviceId());

            try {
                UUID id = dataClient.portsCreate(port.toData());
                return Response.created(
                        ResourceUriBuilder.getPort(getBaseUri(), id))
                        .build();
            } catch (VlanPathExistsException e) {
                throw new BadRequestHttpException(e, e.getMessage());
            }
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
        public List<Port> list()
                throws StateAccessException, SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByBridge(bridgeId);
            ArrayList<Port> ports = new ArrayList<Port>();
            if (ports != null) {
                for (org.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(getBaseUri());
                    ports.add(port);
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for bridge's peer ports.
     */
    @RequestScoped
    public static class BridgePeerPortResource extends AbstractResource {

        private final UUID bridgeId;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public BridgePeerPortResource(RestApiConfig config,
                                      UriInfo uriInfo,
                                      SecurityContext context,
                                      BridgeAuthorizer authorizer,
                                      DataClient dataClient,
                                      @Assisted UUID bridgeId) {
            super(config, uriInfo, context);
            this.authorizer = authorizer;
            this.dataClient = dataClient;
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
        public List<Port> list()
                throws StateAccessException, SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByBridge(bridgeId);
            List<Port> ports = new ArrayList<Port>();
            if (portDataList != null) {
                for (org.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(getBaseUri());
                    ports.add(port);
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for router's ports.
     */
    @RequestScoped
    public static class RouterPortResource extends AbstractResource {

        private final UUID routerId;
        private final Authorizer authorizer;
        private final Validator validator;
        private final DataClient dataClient;

        @Inject
        public RouterPortResource(RestApiConfig config,
                                  UriInfo uriInfo,
                                  SecurityContext context,
                                  RouterAuthorizer authorizer,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID routerId) {
            super(config, uriInfo, context);
            this.authorizer = authorizer;
            this.validator = validator;
            this.dataClient = dataClient;
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
                throws StateAccessException,
                InvalidStateOperationException,
                SerializationException {

            port.setDeviceId(routerId);

            Set<ConstraintViolation<RouterPort>> violations = validator
                    .validate(port);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (dataClient.routersGet(routerId) == null) {
                throw new NotFoundHttpException("Cannot create port for non existent router");
            }

            if (!authorizer.authorize(context, AuthAction.WRITE, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add port to this router.");
            }

            UUID id = dataClient.portsCreate(port.toData());
            return Response.created(
                    ResourceUriBuilder.getPort(getBaseUri(), id))
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
        public List<Port> list()
                throws StateAccessException, SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByRouter(routerId);
            ArrayList<Port> ports = new ArrayList<Port>();
            if (ports != null) {
                for (org.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(getBaseUri());
                    ports.add(port);
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for router peer ports.
     */
    @RequestScoped
    public static class RouterPeerPortResource extends AbstractResource {

        private final UUID routerId;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public RouterPeerPortResource(RestApiConfig config,
                                      UriInfo uriInfo,
                                      SecurityContext context,
                                      RouterAuthorizer authorizer,
                                      DataClient dataClient,
                                      @Assisted UUID routerId) {
            super(config, uriInfo, context);
            this.authorizer = authorizer;
            this.dataClient = dataClient;
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
        public List<Port> list()
                throws StateAccessException, SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByRouter(routerId);
            List<Port> ports = new ArrayList<Port>();
            if (portDataList != null) {
                for (org.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(getBaseUri());
                    ports.add(port);
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for port group ports.
     */
    @RequestScoped
    public static class PortGroupPortResource extends AbstractResource {

        private final UUID portGroupId;
        private final Authorizer portGroupAuthorizer;
        private final Authorizer portAuthorizer;
        private final Validator validator;
        private final DataClient dataClient;

        @Inject
        public PortGroupPortResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     PortGroupAuthorizer portGroupAuthorizer,
                                     PortAuthorizer portAuthorizer,
                                     Validator validator,
                                     DataClient dataClient,
                                     @Assisted UUID portGroupId) {
            super(config, uriInfo, context);
            this.portGroupAuthorizer = portGroupAuthorizer;
            this.portAuthorizer = portAuthorizer;
            this.validator = validator;
            this.dataClient = dataClient;
            this.portGroupId = portGroupId;
        }

        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORTGROUP_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(PortGroupPort portGroupPort)
                throws StateAccessException, SerializationException {

            portGroupPort.setPortGroupId(portGroupId);

            Set<ConstraintViolation<PortGroupPort>> violations = validator
                    .validate(portGroupPort,
                            PortGroupPortCreateGroupSequence.class);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!portGroupAuthorizer.authorize(
                    context, AuthAction.WRITE, portGroupId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to modify this port group's " +
                                "membership.");
            }

            if(!portAuthorizer.authorize(context, AuthAction.WRITE,
                    portGroupPort.getPortId())) {
                throw new ForbiddenHttpException(
                        "Not authorized to modify this port's membership.");
            }

            dataClient.portGroupsAddPortMembership(portGroupId,
                    portGroupPort.getPortId());

            return Response.created(
                    ResourceUriBuilder.getPortGroupPort(getBaseUri(),
                            portGroupId, portGroupPort.getPortId()))
                    .build();
        }


        @DELETE
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Path("{portId}")
        public void delete(@PathParam("portId") UUID portId)
                throws StateAccessException, SerializationException {

            if (!dataClient.portGroupsExists(portGroupId)
                 || !dataClient.portsExists(portId)) {
                return;
            }

            if (!portGroupAuthorizer.authorize(
                    context, AuthAction.WRITE, portGroupId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to modify this port group's " +
                                "membership.");
            }

            if(!portAuthorizer.authorize(context, AuthAction.WRITE, portId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to modify this port's membership.");
            }

            dataClient.portGroupsRemovePortMembership(portGroupId, portId);

        }

        @GET
        @PermitAll
        @Produces( {VendorMediaType.APPLICATION_PORTGROUP_PORT_JSON,
                MediaType.APPLICATION_JSON })
        @Path("{portId}")
        public PortGroupPort get(@PathParam("portId") UUID portId)
            throws StateAccessException, SerializationException {

            if (!dataClient.portGroupsIsPortMember(portGroupId, portId)) {
                throw new NotFoundHttpException(
                        "The requested resource was not found.");
            }

            if (!portGroupAuthorizer.authorize(
                    context, AuthAction.READ, portGroupId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view this port group's " +
                                "membership.");
            }

            PortGroupPort portGroupPort = new PortGroupPort();
            portGroupPort.setPortGroupId(portGroupId);
            portGroupPort.setPortId(portId);
            portGroupPort.setBaseUri(getBaseUri());
            return portGroupPort;
        }

        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PortGroupPort> list() throws StateAccessException,
                                                 SerializationException {

            if (!portGroupAuthorizer.authorize(
                    context, AuthAction.READ, portGroupId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view this port group's " +
                                "membership.");
            }

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByPortGroup(portGroupId);
            List<PortGroupPort> portGroupPorts =
                    new ArrayList<PortGroupPort>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData :
                    portDataList) {
                PortGroupPort portGroupPort = new PortGroupPort();
                portGroupPort.setPortGroupId(portGroupId);
                portGroupPort.setPortId(portData.getId());
                portGroupPort.setBaseUri(getBaseUri());
                portGroupPorts.add(portGroupPort);
            }

            return portGroupPorts;
        }

    }
}
