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

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.VendorMediaType;
import org.midonet.api.l4lb.LoadBalancer;
import org.midonet.api.l4lb.VIP;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.event.topology.LoadBalancerEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;

import javax.annotation.security.RolesAllowed;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.getMessage;

@RequestScoped
public class LoadBalancerResource extends AbstractResource {

    private final LoadBalancerEvent loadBalancerEvent = new LoadBalancerEvent();

    private final ResourceFactory factory;

    @Inject
    public LoadBalancerResource(RestApiConfig config, UriInfo uriInfo,
                                SecurityContext context, DataClient dataClient,
                                ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, null);
        this.factory = factory;
    }

    /**
     * Handler to GETting a list of load balancers
     *
     * @return List of the load balancers.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_LOAD_BALANCER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<LoadBalancer> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.LoadBalancer> dataLoadBalancers;

        dataLoadBalancers = dataClient.loadBalancersGetAll();
        List<LoadBalancer> loadBalancers = new ArrayList<>();
        if (dataLoadBalancers != null) {
            for (org.midonet.cluster.data.l4lb.LoadBalancer dataLoadBalancer:
                    dataLoadBalancers) {
                LoadBalancer loadBalancer =
                        new LoadBalancer(dataLoadBalancer);
                loadBalancer.setBaseUri(getBaseUri());
                loadBalancers.add(loadBalancer);
            }
        }
        return loadBalancers;
    }

    /**
     * Handler to GETting the specific load balancer
     *
     * @param id         Load balancer ID from the request.
     * @return A Load Balancer object
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
            MediaType.APPLICATION_JSON })
    public LoadBalancer get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {
        org.midonet.cluster.data.l4lb.LoadBalancer loadBalancerData =
            dataClient.loadBalancerGet(id);
        if (loadBalancerData == null)
            throwNotFound(id, "load balancer");

        LoadBalancer loadBalancer = new LoadBalancer(loadBalancerData);
        loadBalancer.setBaseUri(getBaseUri());

        return loadBalancer;
    }

    /**
     * Handler to DELETing a load balancer.
     *
     * @param id        Load balancer ID from the request.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
        throws StateAccessException, MappingStatusException,
            InvalidStateOperationException, SerializationException {

        try {
            dataClient.loadBalancerDelete(id);
            loadBalancerEvent.delete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent; do nothing.
        }
    }


    /**
     * Handler to POSTing a load balancer
     *
     * @param loadBalancer The requested load balancer object.
     * @return Response for the POST request.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     * @throws ConflictHttpException
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(LoadBalancer loadBalancer)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException, ConflictHttpException {

        // Router ID can be modified only indirectly, by modifying the
        // router to set its load balancer ID.
        if (loadBalancer.getRouterId() != null) {
            throw new BadRequestHttpException(
                getMessage(MessageProperty.ROUTER_ID_IS_INVALID_IN_LB));
        }

        try {
            UUID id = dataClient.loadBalancerCreate(loadBalancer.toData());
            loadBalancerEvent.create(id, dataClient.loadBalancerGet(id));
            return Response.created(
                    ResourceUriBuilder.getLoadBalancer(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex,
                getMessage(RESOURCE_EXISTS, "load balancer", loadBalancer.getId()));
        }
    }

    /**
     * Handler to PUTing a load balancer
     *
     * @param id            The UUID of the load balancer to be updated.
     * @param loadBalancer  The requested load balancer object.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, LoadBalancer loadBalancer)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException {
        loadBalancer.setId(id);
        try {
            dataClient.loadBalancerUpdate(loadBalancer.toData());
            loadBalancerEvent.update(id, dataClient.loadBalancerGet(id));
        } catch (InvalidStateOperationException ex) {
            throw new BadRequestHttpException(ex,
                getMessage(MessageProperty.ROUTER_ID_IS_INVALID_IN_LB));
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(ex);
        }
    }

    /**
     * Delegate the request handlings of the pools to the sub resource.
     *
     * @param id The UUID of the load balancer which has the associated pools.
     * @return PoolResource.LoadBalancerPoolResource instance.
     */
    @Path("{id}" + ResourceUriBuilder.POOLS)
    public PoolResource.LoadBalancerPoolResource getPoolResource(
            @PathParam("id") UUID id) {
        return factory.getLoadBalancerPoolResource(id);
    }

    /**
     * Handler to GETting a list of VIPs
     *
     * @return The list of the VIPs.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    @Path("{id}" + ResourceUriBuilder.VIPS)
    public List<VIP> listVips(@PathParam("id") UUID loadBalancerId)
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.l4lb.VIP> vipsData;

        vipsData = dataClient.loadBalancerGetVips(loadBalancerId);
        List<VIP> vips = new ArrayList<>();
        if (vipsData != null) {
            for (org.midonet.cluster.data.l4lb.VIP vipData: vipsData) {
                VIP vip = new VIP(vipData);
                vip.setBaseUri(getBaseUri());
                vips.add(vip);
            }
        }

        return vips;
    }
}
