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
import org.midonet.cluster.rest_api.neutron.models.LBListenerV2;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerV2Api;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class LbListenerV2Resource {

    private final LoadBalancerV2Api api;
    private final URI baseUri;

    @Inject
    public LbListenerV2Resource(UriInfo uriInfo, LoadBalancerV2Api api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.LB_LISTENER_JSON_V2)
    public LBListenerV2 get(@PathParam("id") UUID id) {
        LBListenerV2 l = api.getListenerV2(id);
        if (l == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return l;
    }

    @GET
    @Produces(NeutronMediaType.LB_LISTENERS_JSON_V2)
    public List<LBListenerV2> list() {
        return api.getListenersV2();
    }

    @POST
    @Consumes(NeutronMediaType.LB_LISTENER_JSON_V2)
    @Produces(NeutronMediaType.LB_LISTENER_JSON_V2)
    public Response create(LBListenerV2 l) {
        api.createListenerV2(l);
        return Response.created(NeutronUriBuilder.getListenerV2(baseUri, l.id))
                       .entity(l).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteListenerV2(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.LB_LISTENER_JSON_V2)
    @Produces(NeutronMediaType.LB_LISTENER_JSON_V2)
    public Response update(@PathParam("id") UUID id, LBListenerV2 l) {
        api.updateListenerV2(id, l);
        return Response.ok(NeutronUriBuilder.getListenerV2(baseUri, l.id))
                       .entity(l).build();
    }
}
