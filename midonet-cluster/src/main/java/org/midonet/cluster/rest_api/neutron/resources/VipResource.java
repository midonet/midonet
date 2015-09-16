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
import org.midonet.cluster.rest_api.neutron.models.VIP;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerApi;
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class VipResource {

    private final LoadBalancerApi api;
    private final URI baseUri;

    @Inject
    public VipResource(UriInfo uriInfo, NeutronZoomPlugin api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.VIP_JSON_V1)
    public VIP get(@PathParam("id") UUID id) {
        VIP vip = api.getVip(id);
        if (vip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return vip;
    }

    @GET
    @Produces(NeutronMediaType.VIPS_JSON_V1)
    public List<VIP> list() {
        return api.getVips();
    }

    @POST
    @Consumes(NeutronMediaType.VIP_JSON_V1)
    @Produces(NeutronMediaType.VIP_JSON_V1)
    public Response create(VIP vip) {
        api.createVip(vip);
        return Response.created(NeutronUriBuilder.getVip(baseUri, vip.id))
                       .entity(vip).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteVip(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.VIP_JSON_V1)
    @Produces(NeutronMediaType.VIP_JSON_V1)
    public Response update(@PathParam("id") UUID id, VIP vip) {
        api.updateVip(id, vip);
        return Response.ok(NeutronUriBuilder.getVip(baseUri, vip.id))
                       .entity(vip).build();
    }
}
