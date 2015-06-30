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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.ServiceUnavailableHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.VIPDataConverter;
import org.midonet.cluster.rest_api.models.Vip;
import org.midonet.event.topology.VipEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExceptionBase;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.l4lb.MappingStatusException;

import static org.midonet.cluster.rest_api.conversion.VIPDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.VIPDataConverter.toData;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAPPING_STATUS_IS_PENDING;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@RequestScoped
public class VipResource extends AbstractResource {

    private final VipEvent vipEvent = new VipEvent();

    @Inject
    public VipResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, DataClient dataClient,
                       Validator validator) {
        super(config, uriInfo, context, dataClient, validator);
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
    public List<Vip> list() throws StateAccessException,
                                   SerializationException,
                                   IllegalAccessException {
        List<org.midonet.cluster.data.l4lb.VIP> vipsData;

        vipsData = dataClient.vipGetAll();
        List<Vip> vips = new ArrayList<>();
        if (vipsData != null) {
            for (org.midonet.cluster.data.l4lb.VIP vipData: vipsData) {
                vips.add(fromData(vipData, getBaseUri()));
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
    public Vip get(@PathParam("id") UUID id) throws StateAccessException,
                                                    SerializationException,
                                                    IllegalAccessException {
        org.midonet.cluster.data.l4lb.VIP vipData = dataClient.vipGet(id);
        if (vipData == null) {
            throw notFoundException(id, "VIP");
        }
        return VIPDataConverter.fromData(vipData, getBaseUri());
    }

    /**
     * Handler to DELETing a VIP
     *
     * @param id VIP ID from the request.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        try {
            dataClient.vipDelete(id);
            vipEvent.delete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so just ignore.
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
        }
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
    public Response create(Vip vip) throws StateAccessException,
                                           InvalidStateOperationException,
                                           SerializationException,
                                           ServiceUnavailableHttpException,
                                           ConflictHttpException {
        validate(vip);

        try {
            UUID id = dataClient.vipCreate(toData(vip));
            vipEvent.create(id, dataClient.vipGet(id));
            return Response.created(
                    ResourceUriBuilder.getVip(getBaseUri(), id)).build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex,
                getMessage(RESOURCE_EXISTS, "VIP", vip.id));
        } catch (NoStatePathException ex) {
            throw badReqOrNotFoundException(ex, vip.id);
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
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
    public void update(@PathParam("id") UUID id, Vip vip)
            throws StateAccessException, InvalidStateOperationException,
                   SerializationException {
        vip.id = id;
        validate(vip);

        if (dataClient.vipGet(vip.id) == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND,
                                                       "VIP", id));
        }

        if (dataClient.poolGet(vip.poolId) == null) {
            throw new BadRequestHttpException(getMessage(RESOURCE_NOT_FOUND,
                                                         "pool", vip.poolId));
        }

        try {
            dataClient.vipUpdate(toData(vip));
            vipEvent.update(id, dataClient.vipGet(id));
        } catch (NoStatePathException ex) {
            throw badReqOrNotFoundException(ex, vip.id);
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
        }
    }

    /**
     * Sub-resource class for pool's VIPs.
     */
    @RequestScoped
    public static class PoolVipResource extends AbstractResource {
        private final UUID poolId;

        @Inject
        public PoolVipResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               DataClient dataClient,
                               Validator validator,
                               @Assisted UUID id) {
            super(config, uriInfo, context, dataClient, validator);
            this.poolId = id;
        }

        @GET
        @RolesAllowed({ AuthRole.ADMIN })
        @Produces({ VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Vip> list()
            throws StateAccessException, SerializationException,
                   IllegalAccessException {

            List<org.midonet.cluster.data.l4lb.VIP> dataVips;

            try {
                dataVips = dataClient.poolGetVips(poolId);
            } catch (NoStatePathException ex) {
                StatePathExceptionBase.NodeInfo node = ex.getNodeInfo();
                throw notFoundException(node.id, node.nodeType.name);
            }
            List<Vip> vips = new ArrayList<>();
            if (dataVips != null) {
                for (org.midonet.cluster.data.l4lb.VIP dataVip : dataVips) {
                    vips.add(VIPDataConverter.fromData(dataVip, getBaseUri()));
                }
            }
            return vips;
        }

        @POST
        @RolesAllowed({ AuthRole.ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_VIP_JSON,
                    MediaType.APPLICATION_JSON})
        public Response create(Vip vip) throws StateAccessException,
                                               SerializationException {
            vip.poolId = poolId;
            validate(vip);
            try {
                UUID id = dataClient.vipCreate(toData(vip));
                return Response.created(
                        ResourceUriBuilder.getVip(getBaseUri(), id))
                        .build();
            } catch (StatePathExistsException ex) {
                throw new ConflictHttpException(ex,
                    getMessage(RESOURCE_EXISTS, "VIP"));
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex);
            } catch (MappingStatusException ex) {
                throw new ServiceUnavailableHttpException(
                    getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
            }
        }
    }
}
