/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.bgp.rest_api.BgpResource.PortBgpResource;
import com.midokura.midolman.mgmt.network.*;
import com.midokura.midolman.mgmt.network.PortGroupPort.PortGroupPortCreateGroupSequence;
import com.midokura.midolman.mgmt.network.auth.BridgeAuthorizer;
import com.midokura.midolman.mgmt.network.auth.PortAuthorizer;
import com.midokura.midolman.mgmt.network.auth.PortGroupAuthorizer;
import com.midokura.midolman.mgmt.network.auth.RouterAuthorizer;
import com.midokura.midolman.mgmt.rest_api.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.ResourceFactory;
import com.midokura.midolman.mgmt.vpn.rest_api.VpnResource.PortVpnResource;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
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
import java.util.ArrayList;
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
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public PortResource(UriInfo uriInfo, SecurityContext context,
                        PortAuthorizer authorizer, Validator validator,
                        DataClient dataClient, ResourceFactory factory) {
        this.context = context;
        this.uriInfo = uriInfo;
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
            throws StateAccessException, InvalidStateOperationException {

        // Get the port and validate that this can be deleted
        com.midokura.midonet.cluster.data.Port portData =
                dataClient.portsGet(id);
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
    public Port get(@PathParam("id") UUID id) throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this port.");
        }

        com.midokura.midonet.cluster.data.Port portData =
                dataClient.portsGet(id);
        if (portData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        Port port = PortFactory.createPort(portData);
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
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public void link(@PathParam("id") UUID id, Link link)
            throws StateAccessException {

        link.setPortId(id);

        Set<ConstraintViolation<Link>> violations = null;
        if (link.isUnlink()) {
            violations = validator.validate(link,
                    Link.LinkDeleteGroupSequence.class);
        } else {
            violations = validator.validate(link,
                    Link.LinkCreateGroupSequence.class);
        }

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

        if (link.isUnlink()) {
            dataClient.portsUnlink(link.getPortId());
        } else {
            dataClient.portsLink(link.getPortId(), link.getPeerId());
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
        private final DataClient dataClient;

        @Inject
        public BridgePortResource(UriInfo uriInfo,
                                  SecurityContext context,
                                  BridgeAuthorizer authorizer,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID bridgeId) {
            this.context = context;
            this.uriInfo = uriInfo;
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

            UUID id = dataClient.portsCreate(port.toData());
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

            List<com.midokura.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByBridge(bridgeId);
            ArrayList<Port> ports = new ArrayList<Port>();
            if (ports != null) {
                for (com.midokura.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(uriInfo.getBaseUri());
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
    public static class BridgePeerPortResource {

        private final UUID bridgeId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public BridgePeerPortResource(UriInfo uriInfo,
                                      SecurityContext context,
                                      BridgeAuthorizer authorizer,
                                      DataClient dataClient,
                                      @Assisted UUID bridgeId) {
            this.context = context;
            this.uriInfo = uriInfo;
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
        public List<Port> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<com.midokura.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByBridge(bridgeId);
            List<Port> ports = new ArrayList<Port>();
            if (portDataList != null) {
                for (com.midokura.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(uriInfo.getBaseUri());
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
    public static class RouterPortResource {

        private final UUID routerId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final Validator validator;
        private final DataClient dataClient;

        @Inject
        public RouterPortResource(UriInfo uriInfo,
                                  SecurityContext context,
                                  RouterAuthorizer authorizer,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID routerId) {
            this.context = context;
            this.uriInfo = uriInfo;
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

            UUID id = dataClient.portsCreate(port.toData());
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

            List<com.midokura.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByRouter(routerId);
            ArrayList<Port> ports = new ArrayList<Port>();
            if (ports != null) {
                for (com.midokura.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(uriInfo.getBaseUri());
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
    public static class RouterPeerPortResource {

        private final UUID routerId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final DataClient dataClient;

        @Inject
        public RouterPeerPortResource(UriInfo uriInfo,
                                      SecurityContext context,
                                      RouterAuthorizer authorizer,
                                      DataClient dataClient,
                                      @Assisted UUID routerId) {
            this.context = context;
            this.uriInfo = uriInfo;
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
        public List<Port> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            List<com.midokura.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByRouter(routerId);
            List<Port> ports = new ArrayList<Port>();
            if (portDataList != null) {
                for (com.midokura.midonet.cluster.data.Port<?, ?> portData :
                        portDataList) {
                    Port port = PortFactory.createPort(portData);
                    port.setBaseUri(uriInfo.getBaseUri());
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
    public static class PortGroupPortResource {

        private final UUID portGroupId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer portGroupAuthorizer;
        private final Authorizer portAuthorizer;
        private final Validator validator;
        private final DataClient dataClient;

        @Inject
        public PortGroupPortResource(UriInfo uriInfo,
                                      SecurityContext context,
                                      PortGroupAuthorizer portGroupAuthorizer,
                                      PortAuthorizer portAuthorizer,
                                      Validator validator,
                                      DataClient dataClient,
                                      @Assisted UUID portGroupId) {
            this.context = context;
            this.uriInfo = uriInfo;
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
                throws StateAccessException {

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
                    ResourceUriBuilder.getPortGroupPort(uriInfo.getBaseUri(),
                            portGroupId, portGroupPort.getPortId()))
                    .build();
        }


        @DELETE
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Path("{portId}")
        public void delete(@PathParam("portId") UUID portId)
                throws StateAccessException {

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
            throws StateAccessException {

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
            portGroupPort.setBaseUri(uriInfo.getBaseUri());
            return portGroupPort;
        }

        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PortGroupPort> list() throws StateAccessException {

            if (!portGroupAuthorizer.authorize(
                    context, AuthAction.READ, portGroupId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view this port group's " +
                                "membership.");
            }

            List<com.midokura.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByPortGroup(portGroupId);
            List<PortGroupPort> portGroupPorts =
                    new ArrayList<PortGroupPort>(portDataList.size());
            for (com.midokura.midonet.cluster.data.Port<?, ?> portData :
                    portDataList) {
                PortGroupPort portGroupPort = new PortGroupPort();
                portGroupPort.setPortGroupId(portGroupId);
                portGroupPort.setPortId(portData.getId());
                portGroupPort.setBaseUri(uriInfo.getBaseUri());
                portGroupPorts.add(portGroupPort);
            }

            return portGroupPorts;
        }

    }
}
