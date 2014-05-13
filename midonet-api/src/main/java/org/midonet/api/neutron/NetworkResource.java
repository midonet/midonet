/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.neutron.Network;
import org.midonet.cluster.data.neutron.NeutronPlugin;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.*;

public class NetworkResource extends AbstractNeutronResource {

    private final static Logger log = LoggerFactory.getLogger(
            NetworkResource.class);

    @Inject
    public NetworkResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context, NeutronPlugin plugin) {
        super(config, uriInfo, context, plugin);
    }

    @POST
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Network network)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.create entered {}", network);

        try {
            Network net = dataClient.createNetwork(network);

            log.info("NetworkResource.get exiting {}", net);
            return Response.created(
                    NeutronUriBuilder.getNetwork(
                            getBaseUri(), net.id)).entity(net).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.delete entered {}", id);
        dataClient.deleteNetwork(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Network get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.get entered {}", id);

        Network net = dataClient.getNetwork(id);
        if (net == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("NetworkResource.get exiting {}", net);
        return net;
    }

    @GET
    @Produces(NeutronMediaType.NETWORKS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Network> list()
            throws SerializationException, StateAccessException {
        log.info("NetworkResource.list entered");
        return dataClient.getNetworks();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.NETWORK_JSON_V1)
    @Produces(NeutronMediaType.NETWORK_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Network network)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {
        log.info("NetworkResource.update entered {}", network);

        try {
            Network net = dataClient.updateNetwork(id, network);

            log.info("NetworkResource.update exiting {}", net);
            return Response.ok(
                    NeutronUriBuilder.getNetwork(
                            getBaseUri(), net.id)).entity(net).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
