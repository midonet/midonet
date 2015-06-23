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
import java.util.Set;
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
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.IpAddrGroup;
import org.midonet.cluster.rest_api.models.IpAddrGroupAddr;
import org.midonet.cluster.rest_api.models.Ipv4AddrGroupAddr;
import org.midonet.cluster.rest_api.models.Ipv6AddrGroupAddr;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.packets.IPAddr$;

import static org.midonet.cluster.rest_api.validation.MessageProperty.*;

/**
 * Root resource class for IP addr groups.
 */
@RequestScoped
public class IpAddrGroupResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(IpAddrGroupResource.class);

    private final ResourceFactory factory;

    @Inject
    public IpAddrGroupResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               Validator validator, DataClient dataClient,
                               ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    /**
     * Handler to deleting an IP addr group.
     *
     * @param id
     *            IpAddrGroup ID from the request.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        try {
            dataClient.ipAddrGroupsDelete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, but logging due to the fact that this
            // code isn't well-tested.
            log.warn("Caught NoStatePathException. Most likely due to user " +
                     "attempting to delete an IPAddrGroup that already " +
                     "exists, which is fine, but logging exception anyway " +
                     "just in case it has another cause.", ex);
        }
    }

    /**
     * Handler to getting an IP addr group.
     *
     * @param id
     *            IpAddrGroup ID from the request.
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     * @return A IpAddrGroup object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_JSON })
    public IpAddrGroup get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.IpAddrGroup data;

        try {
            data = dataClient.ipAddrGroupsGet(id);
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(ex, "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        IpAddrGroup group = new IpAddrGroup(data);
        group.setBaseUri(getBaseUri());

        return group;
    }

    /**
     * Handler for creating an IP addr group.
     *
     * @param group IpAddrGroup object.
     * @throws StateAccessException Data access error.
     * @return Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_JSON })
    public Response create(IpAddrGroup group)
            throws StateAccessException, SerializationException {

        validate(group, IpAddrGroup.IpAddrGroupCreateGroupSequence.class);

        try {
            UUID id = dataClient.ipAddrGroupsCreate(group.toData());
            return Response.created(
                    ResourceUriBuilder.getIpAddrGroup(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new BadRequestHttpException(ex,
                getMessage(IP_ADDR_GROUP_ID_EXISTS, group.id));
        }
    }

    /**
     * Handler to getting a collection of IpAddrGroup.
     *
     * @throws StateAccessException Data access error.
     * @return A list of IpAddrGroup objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON })
    public List<IpAddrGroup> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.IpAddrGroup> list =
                dataClient.ipAddrGroupsGetAll();

        List<IpAddrGroup> groups = new ArrayList<>();
        for (org.midonet.cluster.data.IpAddrGroup data : list) {
            IpAddrGroup group = new IpAddrGroup(data);
            group.setBaseUri(getBaseUri());
            groups.add(group);
        }
        return groups;
    }

    /**
     * IP addr group addr resource locator
     *
     * @param id
     *            IP addr group ID from the request.
     * @return IpAddrGroupAddrResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.IP_ADDRS)
    public IpAddrGroupAddrResource getIpAddrGroupAddrResource(
            @PathParam("id") UUID id) {
        return factory.getIpAddrGroupAddrResource(id);
    }

    /**
     * IP addr group addr resource locator that includes version
     *
     * @param id IP addr group ID from the request.
     * @param version Version of the IP address
     * @return IpAddrGroupAddrVersionResource object to handle sub-resource
     *          requests.
     */
    @Path("/{id}" + ResourceUriBuilder.VERSIONS + "/{version}"
            + ResourceUriBuilder.IP_ADDRS)
    public IpAddrGroupAddrVersionResource getIpAddrGroupAddrVersionResource(
            @PathParam("id") UUID id, @PathParam("version") int version) {
        if (version != 4 && version != 6) {
            throw new BadRequestHttpException("Invalid IP version: " + version);
        }
        return factory.getIpAddrGroupAddrVersionResource(id, version);
    }

    /**
     * Sub-resource class for IP addr group addresses
     */
    @RequestScoped
    public static class IpAddrGroupAddrResource extends AbstractResource {

        protected final UUID id;

        @Inject
        public IpAddrGroupAddrResource(RestApiConfig config,
                                       UriInfo uriInfo,
                                       SecurityContext context,
                                       DataClient dataClient,
                                       @Assisted UUID ipAddrGroupId) {
            super(config, uriInfo, context, dataClient, null);
            this.id = ipAddrGroupId;
        }

        @GET
        @PermitAll
        @Produces(
                VendorMediaType.APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON)
        public List<IpAddrGroupAddr> list()
                throws SerializationException, StateAccessException {

            List<IpAddrGroupAddr> addrs = new ArrayList<>();
            Set<String> addrSet;
            try {
                addrSet = dataClient.getAddrsByIpAddrGroup(id);
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex, "IP address group does not exist: " + id);
            }

            for (String addr : addrSet) {
                IpAddrGroupAddr a = addr.contains(":") ?
                        new Ipv4AddrGroupAddr(id, addr) : new Ipv6AddrGroupAddr(id, addr);
                a.setBaseUri(getBaseUri());
                addrs.add(a);
            }

            return addrs;
        }

        @POST
        @RolesAllowed({ AuthRole.ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_ADDR_JSON })
        public Response create(IpAddrGroupAddr addr)
                throws StateAccessException, SerializationException {

            if (addr.getVersion() != 4 && addr.getVersion() != 6) {
                throw new BadRequestHttpException(
                        "Invalid IP version: " + addr.getVersion());
            }

            addr.ipAddrGroupId = id;
            try {
                dataClient.ipAddrGroupAddAddr(id, addr.getAddr());
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex, "IP address group" + id);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestHttpException(ex, ex.getMessage());
            }

            addr.setBaseUri(getBaseUri());
            return Response.created(addr.getUri()).build();
        }
    }

    /**
     * Sub-resource class for IP addr group addresses with version
     */
    @RequestScoped
    public static class IpAddrGroupAddrVersionResource
            extends IpAddrGroupAddrResource {

        @Inject
        public IpAddrGroupAddrVersionResource(RestApiConfig config,
                                              UriInfo uriInfo,
                                              SecurityContext context,
                                              DataClient dataClient,
                                              @Assisted UUID ipAddrGroupId) {
            super(config, uriInfo, context, dataClient, ipAddrGroupId);
        }

        @GET
        @PermitAll
        @Path("{addr}")
        @Produces({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_ADDR_JSON })
        public IpAddrGroupAddr get(@PathParam("addr") String addr)
                throws StateAccessException, SerializationException {

            if (!dataClient.ipAddrGroupsExists(id)) {
                throw new NotFoundHttpException(
                        "IP address group does not exist: " + id);
            }

            if (!dataClient.ipAddrGroupHasAddr(id, addr)) {
                throw new NotFoundHttpException(
                        "The IP address group does not contain the " +
                        "specified address: " + addr);
            }

            String canonicalized = IPAddr$.MODULE$.canonicalize(addr);
            IpAddrGroupAddr ipAddrGroupAddr = addr.contains(":") ?
                    new Ipv4AddrGroupAddr(id, canonicalized) :
                    new Ipv6AddrGroupAddr(id, canonicalized);
            ipAddrGroupAddr.setBaseUri(getBaseUri());
            return ipAddrGroupAddr;
        }

        @DELETE
        @RolesAllowed({ AuthRole.ADMIN })
        @Path("{addr}")
        public void delete(@PathParam("addr") String addr)
                throws StateAccessException, SerializationException {
            dataClient.ipAddrGroupRemoveAddr(id, addr);
        }
    }
}
