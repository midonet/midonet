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

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{TunnelZone, TunnelZoneHost}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class TunnelZoneHostResource @Inject()(tunnelZoneId: UUID,
                                       backend: MidonetBackend,
                                       uriInfo: UriInfo)
    extends MidonetResource[TunnelZoneHost](backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON))
    def list(): JList[TunnelZoneHost] = {
        getResource(classOf[TunnelZone], tunnelZoneId)
            .map(_.hosts)
            .getOrThrow
    }

    @GET
    @Produces(Array(APPLICATION_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON))
    @Path("/{hostId}")
    def get(@PathParam("hostId") hostId: UUID): TunnelZoneHost = {
        getResource(classOf[TunnelZone], tunnelZoneId)
            .map(_.hosts.asScala.find(_.hostId == hostId))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @POST
    @Consumes(Array(APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_TUNNEL_ZONE_HOST_JSON))
    def create(tunnelZoneHost: TunnelZoneHost): Response = {
        getResource(classOf[TunnelZone], tunnelZoneId).map(tunnelZone => {
            if (tunnelZone.hosts.asScala.find(_.hostId ==
                                              tunnelZoneHost.hostId).nonEmpty) {
                Response.status(Status.CONFLICT).build()
            } else {
                tunnelZoneHost.create(tunnelZone.id)
                tunnelZoneHost.setBaseUri(uriInfo.getBaseUri)
                tunnelZone.hosts.add(tunnelZoneHost)
                tunnelZone.hostIds.add(tunnelZoneHost.hostId)
                updateResource(tunnelZone,
                               Response.created(tunnelZoneHost.getUri).build())
            }
        }).getOrThrow
    }

    @DELETE
    @Path("/{hostId}")
    def delete(@PathParam("hostId") hostId: UUID): Response = {
        getResource(classOf[TunnelZone], tunnelZoneId).map(tunnelZone => {
            tunnelZone.hosts.asScala.find(_.hostId == hostId)
                .map(tunnelZoneHost => {
                    tunnelZone.hosts.remove(tunnelZoneHost)
                    tunnelZone.hostIds.remove(tunnelZoneHost.hostId)
                    updateResource(tunnelZone)
                })
                .getOrElse(Response.status(Status.NOT_FOUND).build())
        }).getOrThrow
    }

}
