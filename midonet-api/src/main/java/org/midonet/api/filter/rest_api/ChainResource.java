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
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.filter.Chain;
import org.midonet.api.filter.rest_api.RuleResource.ChainRuleResource;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.VendorMediaType;
import org.midonet.brain.services.rest_api.auth.AuthAction;
import org.midonet.brain.services.rest_api.auth.AuthRole;
import org.midonet.brain.services.rest_api.auth.Authorizer;
import org.midonet.brain.services.rest_api.auth.ForbiddenHttpException;
import org.midonet.brain.services.rest_api.filter.auth.ChainAuthorizer;
import org.midonet.brain.services.rest_api.rest_api.NotFoundHttpException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.event.topology.ChainEvent;
import org.midonet.util.serialization.SerializationException;

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

    @Path("/{id}" + ResourceUriBuilder.RULES)
    public ChainRuleResource getRuleResource(@PathParam("id") UUID id) {
        return factory.getChainRuleResource(id);
    }

    /**
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
