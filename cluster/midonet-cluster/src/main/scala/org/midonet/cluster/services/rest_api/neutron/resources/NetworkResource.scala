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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.util
import java.util.UUID
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs.{Consumes, DELETE, GET, POST, PUT, Path, PathParam, Produces}

import com.google.inject.Inject

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{NETWORKS, getUri}
import org.midonet.cluster.rest_api.neutron.models.Network
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class NetworkResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.NETWORK_JSON_V1))
    @Produces(Array(NeutronMediaType.NETWORK_JSON_V1))
    def create(network: Network): Response = {
        val net = api.createNetwork(network)
        Response.created(getUri(uriInfo.getBaseUri, NETWORKS, net.id))
                .entity(net).build
    }

    @POST
    @Consumes(Array(NeutronMediaType.NETWORKS_JSON_V1))
    @Produces(Array(NeutronMediaType.NETWORKS_JSON_V1))
    def createBulk(networks: util.List[Network]): Response = {
        val nets: util.List[Network] = api.createNetworkBulk(networks)
        Response.created(getUri(uriInfo.getBaseUri, NETWORKS))
                .entity(nets).build
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID) {
        api.deleteNetwork(id)
    }

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.NETWORK_JSON_V1))
    def get(@PathParam("id") id: UUID): Network = {
        val net = api.getNetwork(id)
        if (net == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        net
    }

    @GET
    @Produces(Array(NeutronMediaType.NETWORKS_JSON_V1))
    def list: util.List[Network] = {
        api.getNetworks
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(NeutronMediaType.NETWORK_JSON_V1))
    @Produces(Array(NeutronMediaType.NETWORK_JSON_V1))
    def update(@PathParam("id") id: UUID, network: Network): Response = {
        val net = api.updateNetwork(id, network)
        Response.ok(getUri(uriInfo.getBaseUri, NETWORKS, net.id))
                .entity(net).build
    }
}