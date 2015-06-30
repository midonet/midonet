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
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.HostDataConverter;
import org.midonet.cluster.rest_api.models.Host;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.HOST_FLOODING_PROXY_WEIGHT_IS_NULL;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@RequestScoped
public class HostResource extends AbstractResource {

    private final ResourceFactory factory;

    @Inject
    public HostResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, Validator validator,
                        DataClient dataClient,
                        ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_HOST_COLLECTION_JSON_V2,
               VendorMediaType.APPLICATION_HOST_COLLECTION_JSON_V3,
               MediaType.APPLICATION_JSON})
    public List<Host> list()
        throws ForbiddenHttpException, StateAccessException,
               SerializationException, IllegalAccessException {

        List<org.midonet.cluster.data.host.Host> hostConfigs =
                dataClient.hostsGetAll();
        List<Host> hosts = new ArrayList<>();
        for (org.midonet.cluster.data.host.Host hostConfig : hostConfigs) {
            hosts.add(
                HostDataConverter.fromData(hostConfig, getBaseUri())
            );
        }
        return hosts;
    }

    /**
     * Handler to getting a host information.
     *
     * @param id Host ID from the request.
     * @return A Host object.
     * @throws StateAccessException Data access error.
     * @throws NotFoundHttpException Non existent UUID
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_HOST_JSON_V2,
               VendorMediaType.APPLICATION_HOST_JSON_V3,
               MediaType.APPLICATION_JSON})
    public Host get(@PathParam("id") UUID id)
        throws NotFoundHttpException, StateAccessException,
               SerializationException, IllegalAccessException {

        org.midonet.cluster.data.host.Host hostConfig =
                dataClient.hostsGet(id);
        if (hostConfig == null) {
            throw notFoundException(id, "host");
        }
        return HostDataConverter.fromData(hostConfig, getBaseUri());
    }

    /**
     * Handler to deleting a host.
     *
     * @param id Host ID from the request.
     * @return Response object with 204 status code set if successful and 403 is
     *         the deletion could not be executed.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    public Response delete(@PathParam("id") UUID id)
            throws StateAccessException {

        if (dataClient.hostsIsAlive(id)) {
            throw new ForbiddenHttpException("Midolman Agent is still"
                    + " active on this host. You must remove all port "
                    + "bindings on this host and shutdown midolman before"
                    + " deleting this host.");
        }

        if (dataClient.hostsHasPortBindings(id)) {
            throw new ForbiddenHttpException("Port bindings still"
                    + " exist on this host. You must remove all port "
                    + "bindings on this host and shutdown midolman before"
                    + " deleting this host.");
        }

        dataClient.hostsDelete(id);

        return Response.noContent().build();

    }

    /**
     * Interface resource locator for hosts.
     *
     * @param hostId Host ID from the request.
     * @return InterfaceResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.INTERFACES)
    public InterfaceResource getInterfaceResource(
            @PathParam("id") UUID hostId) {

        return factory.getInterfaceResource(hostId);
    }

    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public HostInterfacePortResource getHostInterfacePortResource(
            @PathParam("id") UUID hostId) {
        return factory.getHostInterfacePortResource(hostId);
    }

    /**
     * Update the host information.
     *
     * The update also involves associated values such as the flooding
     * proxy weight.
     * @param id         Host ID from the request.
     * @param host     New flooding proxy weight.
     * @return An updated Host object.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Consumes({VendorMediaType.APPLICATION_HOST_JSON_V2,
               VendorMediaType.APPLICATION_HOST_JSON_V3,
               MediaType.APPLICATION_JSON})
    public Response update(@PathParam("id") UUID id, Host host)
            throws StateAccessException, SerializationException {

        host.id = id;

        validate(host);

        /*
         * currently, the only updatable host information is the flooding
         * proxy weight
         */
        Integer weight = host.floodingProxyWeight;
        if (weight != null) {
            try {
                dataClient.hostsSetFloodingProxyWeight(id, weight);
                return Response.ok().build();
            } catch (NoStatePathException e) {
                throw notFoundException(id, "host");
            }
        } else if (!dataClient.hostsExists(id)) {
            throw notFoundException(id, "host");
        } else {
            throw new BadRequestHttpException(
                getMessage(HOST_FLOODING_PROXY_WEIGHT_IS_NULL));
        }
    }
}
