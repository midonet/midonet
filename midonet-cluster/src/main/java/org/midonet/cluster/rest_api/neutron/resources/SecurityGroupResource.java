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

package org.midonet.cluster.rest_api.neutron.resources;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.SecurityGroup;
import org.midonet.cluster.services.rest_api.neutron.plugin.SecurityGroupApi;
import org.midonet.midolman.serialization.SerializationException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class SecurityGroupResource {

    private final SecurityGroupApi api;
    private final URI baseUri;

    @Inject
    public SecurityGroupResource(UriInfo uriInfo, SecurityGroupApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    public Response create(SecurityGroup sg) {
        SecurityGroup s = api.createSecurityGroup(sg);
        return Response.created(NeutronUriBuilder.getSecurityGroup(baseUri,
                                                                   s.id))
                       .entity(s).build();
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    public Response createBulk(List<SecurityGroup> sgs) {
        List<SecurityGroup> outSgs =
            api.createSecurityGroupBulk(sgs);
        return Response.created(
            NeutronUriBuilder.getSecurityGroups(baseUri))
            .entity(outSgs).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        api.deleteSecurityGroup(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    public SecurityGroup get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        SecurityGroup sg = api.getSecurityGroup(id);
        if (sg == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return sg;
    }

    @GET
    @Produces(NeutronMediaType.SECURITY_GROUPS_JSON_V1)
    public List<SecurityGroup> list()
            throws SerializationException, StateAccessException {
        return api.getSecurityGroups();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_JSON_V1)
    public Response update(@PathParam("id") UUID id, SecurityGroup sg) {
        SecurityGroup s = api.updateSecurityGroup(id, sg);
        return Response.ok(NeutronUriBuilder.getSecurityGroup(baseUri, s.id))
                       .entity(s).build();
    }
}
