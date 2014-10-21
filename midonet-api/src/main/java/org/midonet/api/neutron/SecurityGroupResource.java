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
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.SecurityGroup;
import org.midonet.cluster.data.neutron.SecurityGroupApi;
import org.midonet.event.neutron.SecurityGroupEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.*;

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
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(SecurityGroup sg)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("SecurityGroupResource.create entered {}", sg);

        try {

            SecurityGroup s = api.createSecurityGroup(sg);
            SECURITY_GROUP_EVENT.create(s.id, s);
            log.info("SecurityGroupResource.create exiting {}", s);
            return Response.created(
                    NeutronUriBuilder.getSecurityGroup(
                            getBaseUri(), s.id)).entity(s).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<SecurityGroup> sgs)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("SecurityGroupResource.createBulk entered");

        try {
            List<SecurityGroup> outSgs =
                    api.createSecurityGroupBulk(sgs);
            for (SecurityGroup s : outSgs) {
                SECURITY_GROUP_EVENT.create(s.id, s);
            }
            return Response.created(NeutronUriBuilder.getSecurityGroups(
                    getBaseUri())).entity(outSgs).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
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
    public Response update(@PathParam("id") UUID id, SecurityGroup sg)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("SecurityGroupResource.update entered {}", sg);

        try {

            SecurityGroup s = api.updateSecurityGroup(id, sg);
            SECURITY_GROUP_EVENT.update(id, s);
            log.info("SecurityGroupResource.update exiting {}", s);
            return Response.ok(
                    NeutronUriBuilder.getSecurityGroup(
                            getBaseUri(), s.id)).entity(s).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist");
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
