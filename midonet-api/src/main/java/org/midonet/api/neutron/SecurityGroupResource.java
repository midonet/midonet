/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.api.neutron;

import java.util.List;
import java.util.UUID;

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

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.SecurityGroup;
import org.midonet.cluster.services.rest_api.neutron.plugin.SecurityGroupApi;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.event.neutron.SecurityGroupEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class SecurityGroupResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            SecurityGroupResource.class);
    private final static SecurityGroupEvent SECURITY_GROUP_EVENT =
            new SecurityGroupEvent();

    private final SecurityGroupApi api;

    @Inject
    public SecurityGroupResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context,
                                 SecurityGroupApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(SecurityGroup sg) {
        log.info("SecurityGroupResource.create entered {}", sg);

        SecurityGroup s = api.createSecurityGroup(sg);
        SECURITY_GROUP_EVENT.create(s.id, s);
        log.info("SecurityGroupResource.create exiting {}", s);
        return Response.created(
            NeutronUriBuilder.getSecurityGroup(
                getBaseUri(), s.id)).entity(s).build();
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<SecurityGroup> sgs) {
        log.info("SecurityGroupResource.createBulk entered");

        List<SecurityGroup> outSgs =
            api.createSecurityGroupBulk(sgs);
        for (SecurityGroup s : outSgs) {
            SECURITY_GROUP_EVENT.create(s.id, s);
        }
        return Response.created(NeutronUriBuilder.getSecurityGroups(
            getBaseUri())).entity(outSgs).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupResource.delete entered {}", id);
        api.deleteSecurityGroup(id);
        SECURITY_GROUP_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public SecurityGroup get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupResource.get entered {}", id);

        SecurityGroup sg = api.getSecurityGroup(id);
        if (sg == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("SecurityGroupResource.get exiting {}", sg);
        return sg;
    }

    @GET
    @Produces(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<SecurityGroup> list()
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupResource.list entered");
        return api.getSecurityGroups();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, SecurityGroup sg) {
        log.info("SecurityGroupResource.update entered {}", sg);

        SecurityGroup s = api.updateSecurityGroup(id, sg);
        SECURITY_GROUP_EVENT.update(id, s);
        log.info("SecurityGroupResource.update exiting {}", s);
        return Response.ok(
            NeutronUriBuilder.getSecurityGroup(
                getBaseUri(), s.id)).entity(s).build();
    }
}
