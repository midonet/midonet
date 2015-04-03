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

import org.midonet.api.filter.IpAddrGroup;
import org.midonet.api.filter.IpAddrGroupAddr;
import org.midonet.api.filter.Ipv4AddrGroupAddr;
import org.midonet.api.filter.Ipv6AddrGroupAddr;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.VendorMediaType;
import org.midonet.brain.services.rest_api.auth.AuthRole;
import org.midonet.brain.services.rest_api.rest_api.BadRequestHttpException;
import org.midonet.brain.services.rest_api.rest_api.NotFoundHttpException;
import org.midonet.brain.services.rest_api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.backend.zookeeper.StatePathExistsException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.packets.IPAddr$;
import org.midonet.util.serialization.SerializationException;

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

    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_JSON })
    public IpAddrGroup get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.IpAddrGroup data = null;

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
                MessageProperty.getMessage(MessageProperty.IP_ADDR_GROUP_ID_EXISTS, group.getId()));
        }
    }

    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON })
    public List<IpAddrGroup> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.IpAddrGroup> list =
                dataClient.ipAddrGroupsGetAll();

        List<IpAddrGroup> groups = new ArrayList<IpAddrGroup>();
        for (org.midonet.cluster.data.IpAddrGroup data : list) {
            IpAddrGroup group = new IpAddrGroup(data);
            group.setBaseUri(getBaseUri());
            groups.add(group);
        }
        return groups;
    }

    @Path("/{id}" + ResourceUriBuilder.IP_ADDRS)
    public IpAddrGroupAddrResource getIpAddrGroupAddrResource(
            @PathParam("id") UUID id) {
        return factory.getIpAddrGroupAddrResource(id);
    }

    @Path("/{id}" + ResourceUriBuilder.VERSIONS + "/{version}"
            + ResourceUriBuilder.IP_ADDRS)
    public IpAddrGroupAddrVersionResource getIpAddrGroupAddrVersionResource(
            @PathParam("id") UUID id, @PathParam("version") int version) {
        if (version != 4 && version != 6) {
            throw new BadRequestHttpException("Invalid IP version: " + version);
        }
        return factory.getIpAddrGroupAddrVersionResource(id, version);
    }

    @RequestScoped
    public static class IpAddrGroupAddrResource extends AbstractResource {

        protected final UUID id;

        @Inject
        public IpAddrGroupAddrResource(RestApiConfig config,
                                       UriInfo uriInfo,
                                       SecurityContext context,
                                       DataClient dataClient,
                                       @Assisted UUID ipAddrGroupId) {
            super(config, uriInfo, context, dataClient);
            this.id = ipAddrGroupId;
        }

        @GET
        @PermitAll
        @Produces(
                VendorMediaType.APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON)
        public List<IpAddrGroupAddr> list()
                throws SerializationException, StateAccessException {

            List<IpAddrGroupAddr> addrs = new ArrayList<IpAddrGroupAddr>();
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

            addr.setIpAddrGroupId(id);
            try {
                dataClient.ipAddrGroupAddAddr(id, addr.getAddr());
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex, "IP address group does not exist: " + id);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestHttpException(ex, ex.getMessage());
            }

            addr.setBaseUri(getBaseUri());
            return Response.created(addr.getUri()).build();
        }
    }

    @RequestScoped
    public static class IpAddrGroupAddrVersionResource
            extends IpAddrGroupAddrResource {

        private final int version;

        @Inject
        public IpAddrGroupAddrVersionResource(RestApiConfig config,
                                              UriInfo uriInfo,
                                              SecurityContext context,
                                              DataClient dataClient,
                                              @Assisted UUID ipAddrGroupId,
                                              @Assisted int version) {
            super(config, uriInfo, context, dataClient, ipAddrGroupId);
            this.version = version;
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
