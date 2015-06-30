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

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.filter.rest_api.RuleResource.ChainRuleResource;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.ChainDataConverter;
import org.midonet.cluster.rest_api.models.Chain;
import org.midonet.event.topology.ChainEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

@RequestScoped
public class ChainResource extends AbstractResource {

    private final static ChainEvent chainEvent = new ChainEvent();

    private final ResourceFactory factory;

    @Inject
    public ChainResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, Validator validator,
                         DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    /**
     * Handler to deleting a chain.
     *
     * @param id Chain ID from the request.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.Chain chainData =
            authoriser.tryAuthoriseChain(id, "delete this chain");
        if (chainData == null) {
            return;
        }
        dataClient.chainsDelete(id);
        chainEvent.delete(id);
    }

    /**
     * Handler to getting a chain.
     *
     * @param id Chain ID from the request.
     * @throws StateAccessException Data access error.
     * @return A Chain object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Chain get(@PathParam("id") UUID id) throws StateAccessException,
                                                      SerializationException,
                                                      IllegalAccessException {

        org.midonet.cluster.data.Chain chainData =
            authoriser.tryAuthoriseChain(id, "view this chain");

        if (chainData == null) {
            throw notFoundException(id, "chain");
        }

        return ChainDataConverter.fromData(chainData, getBaseUri());
    }

    /**
     * Rule resource locator for chains.
     *
     * @param id Chain ID from the request.
     * @return ChainRuleResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.RULES)
    public ChainRuleResource getRuleResource(@PathParam("id") UUID id) {
        return factory.getChainRuleResource(id);
    }

    /**
     * Handler for creating a tenant chain.
     *
     * @param chain Chain object.
     * @throws StateAccessException Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Chain chain)
            throws StateAccessException, SerializationException {

        validate(chain);

        if(!authoriser.isAdminOrOwner(chain.tenantId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add chain to this tenant.");
        }

        UUID id = dataClient.chainsCreate(ChainDataConverter.toData(chain));
        chainEvent.create(id, dataClient.chainsGet(id));
        return Response.created(ResourceUriBuilder.getChain(getBaseUri(), id))
                       .build();
    }

    /**
     * Handler to getting a collection of chains.
     *
     * @throws StateAccessException Data access error.
     * @return A list of Chain objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
        MediaType.APPLICATION_JSON })
    public List<Chain> list(@QueryParam("tenant_id") String tenantId)
    throws StateAccessException, SerializationException,
                      IllegalAccessException{

               List<org.midonet.cluster.data.Chain> dataChains = (tenantId == null) ?
                dataClient.chainsGetAll() :
                dataClient.chainsFindByTenant(tenantId);

        List<Chain> chains = new ArrayList<>();
        if (dataChains != null) {
            for (org.midonet.cluster.data.Chain data : dataChains) {
                chains.add(ChainDataConverter.fromData(data, getBaseUri()));
            }
        }

        return chains;
    }
}
