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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.bgp.rest_api.BgpResource;
import org.midonet.api.network.BridgePort;
import org.midonet.api.network.Link;
import org.midonet.api.network.Port;
import org.midonet.api.network.PortFactory;
import org.midonet.api.network.PortType;
import org.midonet.api.network.RouterPort;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.southbound.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.PortGroupPort;
import org.midonet.event.topology.PortEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.VlanPathExistsException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.PORT_GROUP_ID_IS_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@RequestScoped
public class PortResource extends AbstractResource {

    private final static PortEvent portEvent = new PortEvent();

    private final ResourceFactory factory;
    private final VtepClusterClient vtepClient;

    @Inject
    public PortResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, Validator validator,
                        DataClient dataClient, ResourceFactory factory,
                        VtepClusterClient vtepClient) {
        super(config, uriInfo, context, dataClient, validator);
        this.vtepClient = vtepClient;
        this.factory = factory;
    }

    /**
     * Handler to deleting a port.
     *
     * @param id Port ID from the request.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        // Get the port and validate that this can be deleted
        org.midonet.cluster.data.Port<?, ?> portData =
            authoriser.tryAuthorisePort(id, "delete this port");

        if (portData == null) {
            return;
        }

        Port port = PortFactory.convertToApiPort(portData);
        validate(port, Port.PortDeleteGroupSequence.class);

        if (portData instanceof VxLanPort) {
            vtepClient.deleteVxLanPort((VxLanPort)portData);
        } else {
            dataClient.portsDelete(id);
        }
        portEvent.delete(id);
    }

    private org.midonet.cluster.data.Port<?, ?> getPortData(UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.Port<?, ?> portData =
            authoriser.tryAuthorisePort(id, "view this port");

        if (portData == null) {
            throw notFoundException(id, "port");
        }

        return portData;
    }

    /**
     * Handler to getting a v1 port.
     *
     * @param id Port ID from the request.
     * @throws StateAccessException Data access error.
     * @return A Port object.
     */
    @GET
    @Deprecated
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
    public Port getv1(@PathParam("id") UUID id) throws StateAccessException,
                                                       SerializationException {
        Port port = PortFactory.convertToApiPortV1(getPortData(id));
        port.setBaseUri(getBaseUri());
        return port;
    }

    /**
     * Handler to getting a v2 port.
     *
     * @param id Port ID from the request.
     * @throws StateAccessException Data access error.
     * @return A Port object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_PORT_V2_JSON})
    public Port get(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {
        Port port = PortFactory.convertToApiPort(getPortData(id));
        port.setBaseUri(getBaseUri());
        return port;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON,
                MediaType.APPLICATION_JSON})
    public List<Port> list() throws StateAccessException,
                                    SerializationException {
        List<Port> ports = new ArrayList<>();
        for (org.midonet.cluster.data.Port<?, ?> portData :
                dataClient.portsGetAll()) { // NPE-safe
            Port port = PortFactory.convertToApiPort(portData);
            port.setBaseUri(getBaseUri());
            ports.add(port);
        }
        return ports;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON })
    public List<Port> listV1()
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                dataClient.portsGetAll();
        List<Port> ports = new ArrayList<>();
        for (org.midonet.cluster.data.Port<?, ?> portData: portDataList) {
            Port port = PortFactory.convertToApiPortV1(portData);
            port.setBaseUri(getBaseUri());
            ports.add(port);
        }

        return ports;
    }

    /**
     * Handler to updating a port.
     *
     * @param id Port ID from the request.
     * @param port Port object.
     * @throws StateAccessException Data access error.
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                VendorMediaType.APPLICATION_PORT_V2_JSON,
                MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Port port)
            throws StateAccessException, SerializationException {

        port.setId(id);
        validate(port);

        authoriser.tryAuthorisePort(id, "update this port");

        dataClient.portsUpdate(port.toData());
        portEvent.update(id, dataClient.portsGet(id));
    }

    /**
     * Handler to linking ports.
     *
     * @param id Port ID from the request.
     * @param link Link object
     * @throws StateAccessException Data access error.
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
        validate(link, Link.LinkCreateGroupSequence.class);

        authoriser.tryAuthorisePort(id, "link these ports");
        authoriser.tryAuthorisePort(link.getPeerId(), "link these ports");

        dataClient.portsLink(link.getPortId(), link.getPeerId());

        org.midonet.cluster.data.Port<?, ?> portData = dataClient.portsGet(id);
        portEvent.link(id, portData);

        return Response.created(ResourceUriBuilder.getPortLink(getBaseUri(),
                                                               id)).build();
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}/link")
    public void unlink(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {

        // Idempotent operation: if the port does not exists, just return.
        org.midonet.cluster.data.Port<?, ?> portData = dataClient.portsGet(id);
        if (portData == null) {
            return;
        }

        authoriser.tryAuthorisePort(id, "unlink these ports");

        dataClient.portsUnlink(id);

        portData = dataClient.portsGet(id);
        portEvent.unlink(id, portData);
    }

    /**
     * Port resource locator for BGP.
     *
     * @param id Port ID from the request.
     * @return PortBgpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BGP)
    public BgpResource.PortBgpResource getBgpResource(@PathParam("id") UUID id) {
        return factory.getPortBgpResource(id);
    }

    /**
     * Port resource locator for port group.
     *
     * @param id Port ID from the request.
     * @return PortPortGroupResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORT_GROUPS)
    public PortGroupResource.PortPortGroupResource getPortGroupResource(
            @PathParam("id") UUID id) {
        return factory.getPortPortGroupResource(id);
    }

    @Path("/{id}" + ResourceUriBuilder.VTEP_BINDINGS)
    public VxLanPortBindingResource getVxLanPortBindingResource(
            @PathParam("id") UUID id) {
        return factory.getVxLanPortBindingResource(id);
    }

    /**
     * Sub-resource class for bridge's ports.
     */
    @RequestScoped
    public static class BridgePortResource extends AbstractResource {

        private final UUID bridgeId;

        @Inject
        public BridgePortResource(RestApiConfig config, UriInfo uriInfo,
                                  SecurityContext context, Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID bridgeId) {
            super(config, uriInfo, context, dataClient, validator);
            this.bridgeId = bridgeId;
        }

        private Response handleCreatePort(BridgePort port)
                throws SerializationException, StateAccessException {

            port.setDeviceId(bridgeId);
            validate(port);

            if (null == authoriser.tryAuthoriseBridge(
                bridgeId, "add port to the bridge")) {
                throw notFoundException(bridgeId, "bridge");
            }

            // If we are running on a pre-1.2 version, the VLANs path
            // in the bridge may not exist, so let's ensure it exists
            dataClient.ensureBridgeHasVlanDirectory(port.getDeviceId());

            try {
                UUID id = dataClient.portsCreate(port.toData());
                portEvent.create(id, dataClient.portsGet(id));
                return Response.created(
                        ResourceUriBuilder.getPort(getBaseUri(), id)).build();
            } catch (VlanPathExistsException e) {
                throw new BadRequestHttpException(e, e.getMessage());
            }
        }

        /**
         * Handler to create a V1 bridge port.
         *
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @Deprecated
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response createV1(BridgePort port)
                throws StateAccessException, SerializationException {

            // Make sure that BridgePort type is not accepted
            if (port.getType().equals(PortType.BRIDGE)) {
                throw new BadRequestHttpException("Invalid port type.  "
                        + "Only InteriorBridge and ExteriorBridge are "
                        + "accepted.");
            }

            return handleCreatePort(port);
        }

        /**
         * Handler to create a V2 bridge port.
         *
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_V2_JSON })
        public Response create(BridgePort port)
                throws StateAccessException, SerializationException {

            // Make sure that the only type accepted is BridgePort
            if (!port.getType().equals(PortType.BRIDGE)) {
                throw new BadRequestHttpException("Invalid port type.  "
                        + "Only Bridge type is accepted.");
            }

            return handleCreatePort(port);
        }

        /**
         * Handler to list v1 bridge ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @Deprecated
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> listV1()
                throws SerializationException, StateAccessException {

            authoriser.tryAuthoriseBridge(bridgeId, "view these ports");

            List<org.midonet.cluster.data.ports.BridgePort> list =
                    dataClient.portsFindByBridge(bridgeId);
            List<Port> ports = new ArrayList<>(list.size());
            for (org.midonet.cluster.data.ports.BridgePort portData : list) {
                Port port = PortFactory.convertToApiPortV1(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
            }
            return ports;
        }

        /**
         * Handler to list bridge ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON })
        public List<Port> list()
                throws StateAccessException, SerializationException {

            authoriser.tryAuthoriseBridge(bridgeId, "view these ports");

            List<org.midonet.cluster.data.ports.BridgePort> list =
                    dataClient.portsFindByBridge(bridgeId);
            List<Port> ports = new ArrayList<>(list.size());
            for (org.midonet.cluster.data.ports.BridgePort portData : list) {
                Port port = PortFactory.convertToApiPort(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
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

        @Inject
        public BridgePeerPortResource(RestApiConfig config,
                                      UriInfo uriInfo,
                                      SecurityContext context,
                                      DataClient dataClient,
                                      @Assisted UUID bridgeId) {
            super(config, uriInfo, context, dataClient, null);
            this.bridgeId = bridgeId;
        }

        /**
         * Handler to list bridge peer ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @Deprecated
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> listV1()
                throws StateAccessException, SerializationException {

            authoriser.tryAuthoriseBridge(bridgeId, "view these ports");

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByBridge(bridgeId);
            List<Port> ports = new ArrayList<>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : portDataList) {
                Port port = PortFactory.convertToApiPortV1(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
            }
            return ports;
        }

        /**
         * Handler to list bridge peer ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON })
        public List<Port> list()
                throws StateAccessException, SerializationException {

            authoriser.tryAuthoriseBridge(bridgeId, "view these ports");

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByBridge(bridgeId);
            List<Port> ports = new ArrayList<>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : portDataList) {
                Port port = PortFactory.convertToApiPort(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
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

        @Inject
        public RouterPortResource(RestApiConfig config,
                                  UriInfo uriInfo,
                                  SecurityContext context,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID routerId) {
            super(config, uriInfo, context, dataClient, validator);
            this.routerId = routerId;
        }

        private Response handleCreatePort(RouterPort port)
                throws SerializationException, StateAccessException {

            port.setDeviceId(routerId);
            validate(port);

            if (dataClient.routersGet(routerId) == null) {
                throw new NotFoundHttpException(
                        "Cannot create port for non existent router");
            }

            authoriser.tryAuthoriseRouter(routerId, "create this port");

            UUID id = dataClient.portsCreate(port.toData());
            portEvent.create(id, dataClient.portsGet(id));
            return Response.created(
                    ResourceUriBuilder.getPort(getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to create a V1 router port.
         *
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @Deprecated
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                    MediaType.APPLICATION_JSON })
        public Response createV1(RouterPort port)
                throws StateAccessException, SerializationException {

            // Make sure that BridgePort type is not accepted
            if (port.getType().equals(PortType.ROUTER)) {
                throw new BadRequestHttpException("Invalid port type.  "
                        + "Only InteriorRouter and ExteriorRouter are "
                        + "accepted.");
            }

            return handleCreatePort(port);
        }

        /**
         * Handler to create a V2 router port.
         *
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_V2_JSON })
        public Response create(RouterPort port) throws StateAccessException,
                                                       SerializationException {

            // Make sure that the only type accepted is RouterPort
            if (!port.getType().equals(PortType.ROUTER)) {
                throw new BadRequestHttpException("Invalid port type.  "
                        + "Only Router type is accepted.");
            }

            return handleCreatePort(port);
        }

        /**
         * Handler to list V1 router ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @Deprecated
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> listV1()
                throws StateAccessException, SerializationException {

            authoriser.tryAuthoriseRouter(routerId, "view these ports");

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByRouter(routerId);
            ArrayList<Port> ports = new ArrayList<>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : portDataList) {
                Port port = PortFactory.convertToApiPortV1(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
            }
            return ports;
        }

        /**
         * Handler to list V2 router ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON })
        public List<Port> list()
                throws StateAccessException, SerializationException {
            authoriser.tryAuthoriseRouter(routerId, "create these ports");

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindByRouter(routerId);
            ArrayList<Port> ports = new ArrayList<>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : portDataList) {
                 Port port = PortFactory.convertToApiPort(portData);
                 port.setBaseUri(getBaseUri());
                 ports.add(port);
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

        @Inject
        public RouterPeerPortResource(RestApiConfig config,
                                      UriInfo uriInfo,
                                      SecurityContext context,
                                      DataClient dataClient,
                                      @Assisted UUID routerId) {
            super(config, uriInfo, context, dataClient, null);
            this.routerId = routerId;
        }

        /**
         * Handler to list V1 router peer ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @Deprecated
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON })
        public List<Port> listV1()
                throws StateAccessException, SerializationException {

            authoriser.tryAuthoriseRouter(routerId, "view these ports");

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByRouter(routerId);
            List<Port> ports = new ArrayList<>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : portDataList) {
                Port port = PortFactory.convertToApiPortV1(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
            }
            return ports;
        }

        /**
         * Handler to list router peer ports.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Port objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON })
        public List<Port> list() throws StateAccessException,
                                        SerializationException {

            authoriser.tryAuthoriseRouter(routerId, "view these ports");

            List<org.midonet.cluster.data.Port<?, ?>> portDataList =
                    dataClient.portsFindPeersByRouter(routerId);
            List<Port> ports = new ArrayList<>(portDataList.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : portDataList) {
                Port port = PortFactory.convertToApiPort(portData);
                port.setBaseUri(getBaseUri());
                ports.add(port);
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

        @Inject
        public PortGroupPortResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     Validator validator,
                                     DataClient dataClient,
                                     @Assisted UUID portGroupId) {
            super(config, uriInfo, context, dataClient, validator);
            this.portGroupId = portGroupId;
        }

        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORTGROUP_PORT_JSON,
                    MediaType.APPLICATION_JSON })
        public Response create(PortGroupPort portGroupPort)
                throws StateAccessException, SerializationException {

            portGroupPort.portGroupId = portGroupId;
            validate(portGroupPort);

            if (!dataClient.portGroupsExists(portGroupId)) {
                throw new NotFoundHttpException(
                    getMessage(PORT_GROUP_ID_IS_INVALID));
            }

            authoriser.tryAuthorisePortGroup(
                portGroupId, "modify this port group's membership");

            authoriser.tryAuthorisePort(
                portGroupPort.portId, "modify this port's memberships");

            dataClient.portGroupsAddPortMembership(portGroupId,
                                                   portGroupPort.portId);

            return Response.created(ResourceUriBuilder.getPortGroupPort(
                getBaseUri(), portGroupId, portGroupPort.portId)).build();
        }


        @DELETE
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Path("{portId}")
        public void delete(@PathParam("portId") UUID portId)
                throws StateAccessException, SerializationException {

            if (!dataClient.portGroupsExists(portGroupId) ||
                !dataClient.portsExists(portId)) {
                return;
            }

            authoriser.tryAuthorisePortGroup(
                portGroupId, "modify this port group's membership.");

            authoriser.tryAuthorisePort(
                portId, "modify this port's membership.");

            dataClient.portGroupsRemovePortMembership(portGroupId, portId);

        }

        @GET
        @PermitAll
        @Produces( {VendorMediaType.APPLICATION_PORTGROUP_PORT_JSON,
                    MediaType.APPLICATION_JSON })
        @Path("{portId}")
        public PortGroupPort get(@PathParam("portId") UUID portId)
            throws Exception {

            if (!dataClient.portGroupsIsPortMember(portGroupId, portId)) {
                throw new NotFoundHttpException(
                    "The requested resource was not found.");
            }

            authoriser.tryAuthorisePortGroup(
                portGroupId, "view this port group's membership");

            PortGroupPort portGroupPort = new PortGroupPort();
            portGroupPort.portGroupId = portGroupId;
            portGroupPort.portId = portId;
            portGroupPort.setBaseUri(getBaseUri());
            return portGroupPort;
        }

        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON })
        public List<PortGroupPort> list() throws Exception {

            authoriser.tryAuthorisePortGroup(
                portGroupId, "view this port group's membership");

            List<org.midonet.cluster.data.Port<?, ?>> list =
                    dataClient.portsFindByPortGroup(portGroupId);
            List<PortGroupPort> portGroupPorts = new ArrayList<>(list.size());
            for (org.midonet.cluster.data.Port<?, ?> portData : list) {
                PortGroupPort portGroupPort = new PortGroupPort();
                portGroupPort.portGroupId = portGroupId;
                portGroupPort.portId = portData.getId();
                portGroupPort.setBaseUri(getBaseUri());
                portGroupPorts.add(portGroupPort);
            }

            return portGroupPorts;
        }

    }
}
