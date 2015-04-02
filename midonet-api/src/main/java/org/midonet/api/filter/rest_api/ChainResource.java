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
package org.midonet.api.filter.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.filter.Chain;
import org.midonet.api.filter.auth.ChainAuthorizer;
import org.midonet.api.filter.rest_api.RuleResource.ChainRuleResource;
import org.midonet.api.rest_api.*;
import org.midonet.event.topology.ChainEvent;
import org.midonet.cluster.DataClient;
import org.midonet.util.serialization.SerializationException;
import org.midonet.cluster.backend.zookeeper.StateAccessException;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for chains.
 */
@RequestScoped
public class ChainResource extends AbstractResource {

    private final static ChainEvent chainEvent = new ChainEvent();

    private final ChainAuthorizer authorizer;
    private final ResourceFactory factory;

    @Inject
    public ChainResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context,
                         ChainAuthorizer authorizer, Validator validator,
                         DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.authorizer = authorizer;
        this.factory = factory;
    }

    /**
     * Handler to deleting a chain.
     *
     * @param id
     *            Chain ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.Chain chainData =
                dataClient.chainsGet(id);
        if (chainData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this chain.");
        }

        dataClient.chainsDelete(id);
        chainEvent.delete(id);
    }

    /**
     * Handler to getting a chain.
     *
     * @param id
     *            Chain ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Chain object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Chain get(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this chain.");
        }

        org.midonet.cluster.data.Chain chainData =
                dataClient.chainsGet(id);
        if (chainData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Chain chain = new Chain(chainData);
        chain.setBaseUri(getBaseUri());

        return chain;
    }

    /**
     * Rule resource locator for chains.
     *
     * @param id
     *            Chain ID from the request.
     * @return ChainRuleResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.RULES)
    public ChainRuleResource getRuleResource(@PathParam("id") UUID id) {
        return factory.getChainRuleResource(id);
    }

    /**
     * Handler for creating a tenant chain.
     *
     * @param chain
     *            Chain object.
     * @throws StateAccessException
     *             Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Chain chain)
            throws StateAccessException, SerializationException {

        validate(chain, Chain.ChainGroupSequence.class);

        if (!Authorizer.isAdminOrOwner(context, chain.getTenantId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to add chain to this tenant.");
        }

        UUID id = dataClient.chainsCreate(chain.toData());
        chainEvent.create(id, dataClient.chainsGet(id));
        return Response.created(
                ResourceUriBuilder.getChain(getBaseUri(), id))
                .build();
    }

    /**
     * Handler to getting a collection of chains.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Chain objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Chain> list(@QueryParam("tenant_id") String tenantId)
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.Chain> dataChains = (tenantId == null) ?
                dataClient.chainsGetAll() :
                dataClient.chainsFindByTenant(tenantId);

        List<Chain> chains = new ArrayList<>();
        if (dataChains != null) {
            for (org.midonet.cluster.data.Chain dataChain : dataChains) {
                Chain chain = new Chain(dataChain);
                chain.setBaseUri(getBaseUri());
                chains.add(chain);
            }
        }

        return chains;
    }
}
