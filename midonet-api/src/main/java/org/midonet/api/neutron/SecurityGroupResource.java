/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.NeutronPlugin;
import org.midonet.cluster.data.neutron.SecurityGroup;
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

public class SecurityGroupResource extends AbstractNeutronResource {

    private final static Logger log = LoggerFactory.getLogger(
            SecurityGroupResource.class);

    @Inject
    public SecurityGroupResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context, 
                                 NeutronPlugin plugin) {
        super(config, uriInfo, context, plugin);
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

            SecurityGroup s = dataClient.createSecurityGroup(sg);

            log.info("SecurityGroupResource.get exiting {}", s);
            return Response.created(
                    NeutronUriBuilder.getSecurityGroup(
                            getBaseUri(), s.id)).entity(s).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(getMessage(RESOURCE_EXISTS));
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
                    dataClient.createSecurityGroupBulk(sgs);

            return Response.created(NeutronUriBuilder.getSecurityGroups(
                    getBaseUri())).entity(outSgs).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupResource.delete entered {}", id);
        dataClient.deleteSecurityGroup(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public SecurityGroup get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupResource.get entered {}", id);

        SecurityGroup sg = dataClient.getSecurityGroup(id);
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
        return dataClient.getSecurityGroups();
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

            SecurityGroup s = dataClient.updateSecurityGroup(id, sg);

            log.info("SecurityGroupResource.update exiting {}", s);
            return Response.ok(
                    NeutronUriBuilder.getSecurityGroup(
                            getBaseUri(), s.id)).entity(s).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
