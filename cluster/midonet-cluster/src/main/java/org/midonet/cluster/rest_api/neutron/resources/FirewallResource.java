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
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Firewall;
import org.midonet.cluster.services.rest_api.neutron.plugin.FirewallApi;

public class FirewallResource {

    private final FirewallApi api;
    private final URI baseUri;

    @Inject
    public FirewallResource(UriInfo uriInfo, FirewallApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.FIREWALL_JSON_V1)
    @Produces(NeutronMediaType.FIREWALL_JSON_V1)
    public Response create(Firewall firewall) {
        api.createFirewall(firewall);
        return Response.created(
            NeutronUriBuilder.getFirewall(baseUri, firewall.id))
            .entity(firewall).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteFirewall(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.FIREWALL_JSON_V1)
    @Produces(NeutronMediaType.FIREWALL_JSON_V1)
    public Response update(@PathParam("id") UUID id, Firewall firewall) {
        firewall.id = id;
        api.updateFirewall(firewall);
        return Response.ok(NeutronUriBuilder.getFirewall(baseUri, firewall.id))
                       .entity(firewall).build();
    }
}
