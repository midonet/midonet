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
package org.midonet.api.host.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.host.HostInterfacePort;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.event.topology.PortEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.midonet.cluster.VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON;
import static org.midonet.cluster.VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON;

/**
 * REST API handler for host interface port mapping.
 */
@RequestScoped
public class HostInterfacePortResource extends AbstractResource {

    private final static PortEvent portEvent = new PortEvent();
    private final UUID hostId;

    @Inject
    public HostInterfacePortResource(RestApiConfig config,
                                     UriInfo uriInfo,
                                     SecurityContext context,
                                     Validator validator,
                                     DataClient dataClient,
                                     @Assisted UUID hostId) {
        super(config, uriInfo, context, dataClient, validator);
        this.hostId = hostId;
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({ APPLICATION_HOST_INTERFACE_PORT_JSON,
                   APPLICATION_JSON })
    public Response create(HostInterfacePort map)
            throws StateAccessException, SerializationException {

        map.setHostId(hostId);
        validate(map, HostInterfacePort.HostInterfacePortCreateGroup.class);

        dataClient.hostsAddVrnPortMapping(hostId, map.getPortId(),
                map.getInterfaceName());
        portEvent.bind(map.getPortId(), map);

        return Response.created(
                ResourceUriBuilder.getHostInterfacePort(getBaseUri(),
                        hostId, map.getPortId()))
                .build();
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{portId}")
    public void delete(@PathParam("portId") UUID portId)
            throws StateAccessException, SerializationException {

        if (!dataClient.hostsVirtualPortMappingExists(hostId, portId)) {
            return;
        }

        dataClient.hostsDelVrnPortMapping(hostId, portId);
        portEvent.unbind(portId);
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
               APPLICATION_JSON})
    public List<HostInterfacePort> list()
            throws StateAccessException, SerializationException {

        List<VirtualPortMapping> mapConfigs =
                dataClient.hostsGetVirtualPortMappingsByHost(hostId);
        List<HostInterfacePort> maps = new ArrayList<HostInterfacePort>();
        for (VirtualPortMapping mapConfig : mapConfigs) {
            HostInterfacePort map = new HostInterfacePort(
                    hostId, mapConfig);
            map.setBaseUri(getBaseUri());
            maps.add(map);
        }

        return maps;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{portId}")
    @Produces({APPLICATION_HOST_INTERFACE_PORT_JSON,
               APPLICATION_JSON})
    public HostInterfacePort get(@PathParam("portId") UUID portId)
        throws StateAccessException, SerializationException {

        VirtualPortMapping data = dataClient.hostsGetVirtualPortMapping(
                hostId, portId);
        if (null == data) {
            throw new NotFoundHttpException("Mapping does not exist");
        }

        HostInterfacePort map = new HostInterfacePort(hostId, data);
        map.setBaseUri(getBaseUri());
        return map;
    }
}
