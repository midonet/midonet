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

import java.net.URI
import java.util
import java.util.UUID
import javax.ws.rs._
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.QOSPolicy
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.QOSApi

class QOSPolicyResource @Inject()(uriInfo: UriInfo,
                                  private val api: QOSApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_QOS_POLICY_JSON_V1))
    def get(@PathParam("id") id: UUID): QOSPolicy = {
        val qosPolicy = api.getQOSPolicy(id)
        if (qosPolicy == null)
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id))
        qosPolicy
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_QOS_POLICIES_JSON_V1))
    def list: util.List[QOSPolicy] = api.getQOSPolicies

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_QOS_POLICY_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_QOS_POLICY_JSON_V1))
    def create(qosPolicy: QOSPolicy): Response = {
        api.createQOSPolicy(qosPolicy)
        Response.created(
            NeutronUriBuilder.getQOSPolicy(baseUri, qosPolicy.id))
            .entity(qosPolicy).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_QOS_POLICY_JSON_V1))
    def update(@PathParam("id") id: UUID, qosPolicy: QOSPolicy): Response = {
        if (qosPolicy.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateQOSPolicy(qosPolicy)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteQOSPolicy(id)
        Response.noContent().build()
    }
}
