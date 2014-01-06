/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.VIP;
import org.midonet.api.rest_api.*;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import static org.midonet.api.validation.MessageProperty.getMessage;

@RequestScoped
public class VipResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(VIP.class);

    private final Validator validator;
    private final DataClient dataClient;

    @Inject
    public VipResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, Validator validator,
                       DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context);
        this.validator = validator;
        this.dataClient = dataClient;
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
    @Produces({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public List<VIP> list()
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.l4lb.VIP> vipsData;

        vipsData = dataClient.vipGetAll();
        List<VIP> vips = new ArrayList<VIP>();
        if (vipsData != null) {
            for (org.midonet.cluster.data.l4lb.VIP vipData: vipsData) {
                VIP vip = new VIP(vipData);
                vip.setBaseUri(getBaseUri());
                vips.add(vip);
            }

        }

        return vips;
    }

    /**
     * Handler to GETing the specific VIP
     *
     * @param id VIP ID from the request.
     * @return  The VIP object.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public VIP get(@PathParam("id") UUID id)
        throws StateAccessException, SerializationException {
        org.midonet.cluster.data.l4lb.VIP vipData = dataClient.vipGet(id);

        if (vipData == null) {
            throw new NotFoundHttpException(getMessage(
                    MessageProperty.RESOURCE_NOT_FOUND, "VIP", id));
        }
        VIP vip = new VIP(vipData);
        vip.setBaseUri(getBaseUri());

        return vip;
    }

    /**
     * Handler to DELETing a VIP
     *
     * @param id VIP ID from the request.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException {
        org.midonet.cluster.data.l4lb.VIP vipData = dataClient.vipGet(id);

        if (vipData == null) {
            return;
        }
        dataClient.vipDelete(id);
    }

    /**
     * Handler to POSTing a VIP
     *
     * @param vip The requested VIP object.
     * @return Response for the POST request.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     * @throws ConflictHttpException
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(VIP vip)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException,ConflictHttpException {
        try {
            Set<ConstraintViolation<VIP>> violations = validator.validate(vip);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }
            UUID id = dataClient.vipCreate(vip.toData());
            return Response.created(
                    ResourceUriBuilder.getVip(getBaseUri(), id)).build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(getMessage(
                    MessageProperty.RESOURCE_EXISTS,
                    "VIP", vip.getId()));
        } catch (NoStatePathException ex) {
            if (ex.getPath().matches(".*load_balancers.*vips"))
                throw new NotFoundHttpException(getMessage(
                        MessageProperty.RESOURCE_NOT_FOUND,
                        "load balancer", vip.getLoadBalancerId()));
            if (ex.getPath().matches(".*pools.*vips"))
                throw new NotFoundHttpException(getMessage(
                        MessageProperty.RESOURCE_NOT_FOUND,
                        "pool", vip.getPoolId()));

            log.error("Unexpected exception", ex);
            throw new RuntimeException();
        }
    }

    /**
     * Handler to PUTing a VIP
     *
     * @param id  The UUID of the VIP to be updated.
     * @param vip The requested VIP object.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, VIP vip)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException {
        vip.setId(id);
        Set<ConstraintViolation<VIP>> violations = validator.validate(vip);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }
        dataClient.vipUpdate(vip.toData());
    }
}
