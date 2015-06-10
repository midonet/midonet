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
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{SUBNETS, getUri}
import org.midonet.cluster.rest_api.neutron.models.Subnet
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.StateAccessException

class SubnetResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.SUBNET_JSON_V1))
    @Produces(Array(NeutronMediaType.SUBNET_JSON_V1))
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def create(subnet: Subnet): Response = {
        val sub = api.createSubnet(subnet)
        Response.created(getUri(uriInfo.getBaseUri, SUBNETS, sub.id))
                .entity(sub).build
    }

    @POST
    @Consumes(Array(NeutronMediaType.SUBNETS_JSON_V1))
    @Produces(Array(NeutronMediaType.SUBNETS_JSON_V1))
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def createBulk(subnets: util.List[Subnet]): Response = {
        val nets: util.List[Subnet] = api.createSubnetBulk(subnets)
        Response.created(getUri(uriInfo.getBaseUri, SUBNETS))
                .entity(nets).build
    }

    @DELETE
    @Path("{id}")
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def delete(@PathParam("id") id: UUID): Unit = api.deleteSubnet(id)

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.SUBNET_JSON_V1))
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def get(@PathParam("id") id: UUID): Subnet = {
        val sub = api.getSubnet(id)
        if (sub == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        sub
    }

    @GET
    @Produces(Array(NeutronMediaType.SUBNETS_JSON_V1))
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def list: util.List[Subnet] = api.getSubnets

    @PUT
    @Path("{id}")
    @Consumes(Array(NeutronMediaType.SUBNET_JSON_V1))
    @Produces(Array(NeutronMediaType.SUBNET_JSON_V1))
    def update(@PathParam("id") id: UUID, subnet: Subnet): Response = {
        val sub = api.updateSubnet(id, subnet)
        Response.ok(getUri(uriInfo.getBaseUri, SUBNETS, sub.id))
                .entity(sub).build
    }
}