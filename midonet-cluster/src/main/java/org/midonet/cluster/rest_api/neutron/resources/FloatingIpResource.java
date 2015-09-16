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

import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.FloatingIp;
import org.midonet.cluster.services.rest_api.neutron.plugin.L3Api;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class FloatingIpResource {

    private final L3Api api;
    private final URI baseUri;

    @Inject
    public FloatingIpResource(UriInfo uriInfo, L3Api api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.FLOATING_IP_JSON_V1)
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    public Response create(FloatingIp floatingIp) {
        FloatingIp fip = api.createFloatingIp(floatingIp);
        return Response.created(NeutronUriBuilder.getFloatingIp(baseUri,
                                                                fip.id))
                       .entity(fip).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteFloatingIp(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    public FloatingIp get(@PathParam("id") UUID id) {
        FloatingIp fip = api.getFloatingIp(id);
        if (fip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return fip;
    }

    @GET
    @Produces(NeutronMediaType.FLOATING_IPS_JSON_V1)
    public List<FloatingIp> list() {
        return api.getFloatingIps();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.FLOATING_IP_JSON_V1)
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    public Response update(@PathParam("id") UUID id, FloatingIp floatingIp) {
        FloatingIp fip = api.updateFloatingIp(id, floatingIp);
        return Response.ok(NeutronUriBuilder.getFloatingIp(baseUri,
                                                           fip.id))
                       .entity(fip).build();

    }
}
