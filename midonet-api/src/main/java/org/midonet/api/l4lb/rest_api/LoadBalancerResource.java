/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.sun.jersey.api.NotFoundException;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
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
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.getMessage;

@RequestScoped
public class LoadBalancerResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(LoadBalancerResource.class);

    private final DataClient dataClient;

    @Inject
    public LoadBalancerResource(RestApiConfig config, UriInfo uriInfo,
                                SecurityContext context, DataClient dataClient,
                                ResourceFactory factory) {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
    }

    /**
     * Handler to GETting a list of load balancers
     *
     * @return List of the load blanacers.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
            MediaType.APPLICATION_JSON })
    public List<LoadBalancer> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.LoadBalancer> dataLoadBalancers;

        dataLoadBalancers = dataClient.loadBalancersGetAll();
        List<LoadBalancer> loadBalancers = new ArrayList<LoadBalancer>();
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
        if (loadBalancerData == null) {
            throw new NotFoundHttpException(getMessage(
                    MessageProperty.RESOURCE_NOT_FOUND, "load balancer", id));
        }

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
        throws StateAccessException,
            InvalidStateOperationException, SerializationException {
        org.midonet.cluster.data.l4lb.LoadBalancer dataLoadBalancer =
                dataClient.loadBalancerGet(id);
        if (dataLoadBalancer == null) {
            return;
        }
        dataClient.loadBalancerDelete(id);
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
            return Response.created(
                    ResourceUriBuilder.getLoadBalancer(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(getMessage(
                    MessageProperty.RESOURCE_EXISTS,
                    "load balancer", loadBalancer.getId()));
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
        } catch (InvalidStateOperationException ex) {
            throw new BadRequestHttpException(
                    getMessage(MessageProperty.ROUTER_ID_IS_INVALID_IN_LB));

        }
    }

    @GET
    @PermitAll
    @Path("{id}" + ResourceUriBuilder.VIPS)
    @Produces({VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    public List<VIP> listVips(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        // TODO: Authorization.

        List<org.midonet.cluster.data.l4lb.VIP> dataVips = null;
        try {
            dataVips = dataClient.loadBalancerGetVips(id);
        } catch (NoStatePathException ex) {
            if (ex.getPath().matches(".*load_balancers.*vips")) {
                throw new NotFoundHttpException(getMessage(
                        MessageProperty.RESOURCE_NOT_FOUND, "load balancer", id));
            }
            throw ex;
        }

        List<VIP> vips = new ArrayList<>(dataVips.size());
        for (org.midonet.cluster.data.l4lb.VIP dataVip : dataVips) {
            VIP vip = new VIP(dataVip);
            vip.setBaseUri(getBaseUri());
            vips.add(vip);
        }

        return vips;
    }
}
