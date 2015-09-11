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

package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, Set => JSet, UUID}
import javax.validation.ConstraintViolation
import javax.ws.rs._
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{TunnelZone, TunnelZoneHost}
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkNoContentResponse, ResourceContext}

@RequestScoped
class TunnelZoneHostResource @Inject()(tunnelZoneId: UUID,
                                       resContext: ResourceContext)
    extends MidonetResource[TunnelZoneHost](resContext) {

    @GET
    @Produces(Array(APPLICATION_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON))
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): TunnelZoneHost = {
        val hostId = UUID.fromString(id)
        val tzh = getResource(classOf[TunnelZone], tunnelZoneId)
            .map(_.tzHosts.asScala.find(_.hostId == hostId))
            .getOrThrow
            .getOrElse(throw new NotFoundHttpException("Resource not found"))
        tzh.tunnelZoneId = tunnelZoneId
        tzh.setBaseUri(resContext.uriInfo.getBaseUri)
        tzh
    }

    @GET
    @Produces(Array(APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[TunnelZoneHost] = {
        getResource(classOf[TunnelZone], tunnelZoneId)
            .map(_.tzHosts)
            .getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_TUNNEL_ZONE_HOST_JSON))
    override def create(tunnelZoneHost: TunnelZoneHost,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        val validator = resContext.validator
        val violations: JSet[ConstraintViolation[TunnelZoneHost]] =
            validator.validate(tunnelZoneHost)
        if (!violations.isEmpty) {
            throw new BadRequestHttpException(violations)
        }

        getResource(classOf[TunnelZone], tunnelZoneId).map(tunnelZone => {
            if (tunnelZone.tzHosts.exists(_.hostId == tunnelZoneHost.hostId)) {
                Response.status(Status.CONFLICT).build()
            } else {
                tunnelZoneHost.create(tunnelZone.id)
                tunnelZoneHost.setBaseUri(resContext.uriInfo.getBaseUri)
                tunnelZone.tzHosts.add(tunnelZoneHost)
                tunnelZone.hostIds.add(tunnelZoneHost.hostId)
                updateResource(tunnelZone,
                               Response.created(tunnelZoneHost.getUri).build())
            }
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        val hostId = UUID.fromString(id)
        getResource(classOf[TunnelZone], tunnelZoneId).map(tunnelZone => {
            tunnelZone.tzHosts.asScala.find(_.hostId == hostId)
                .map(tunnelZoneHost => {
                    tunnelZone.tzHosts.remove(tunnelZoneHost)
                    tunnelZone.hostIds.remove(tunnelZoneHost.hostId)
                    updateResource(tunnelZone, OkNoContentResponse)
                })
                .getOrElse(Response.status(Status.NOT_FOUND).build())
        }).getOrThrow
    }

}
