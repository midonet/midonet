package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.TunnelZone
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class TunnelZoneResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                    APPLICATION_JSON))
    def list(): JList[TunnelZone] = {
        listResources(classOf[TunnelZone]).getOrThrow.asJava
    }

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_TUNNEL_ZONE_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): TunnelZone = {
        getResource(classOf[TunnelZone], id).getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_TUNNEL_ZONE_JSON,
                    APPLICATION_JSON))
    def create(tunnelZone: TunnelZone): Response = {
        tunnelZone.create()
        createResource(tunnelZone)
    }

    @PUT
    @Consumes(Array(APPLICATION_TUNNEL_ZONE_JSON,
                    APPLICATION_JSON))
    @Path("{id}")
    def update(@PathParam("id") id: UUID, tunnelZone: TunnelZone): Response = {
        getResource(classOf[TunnelZone], id).map(current => {
            tunnelZone.update(id, tunnelZone)
            updateResource(tunnelZone)
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        deleteResource(classOf[TunnelZone], id)
    }

    @Path("{id}/hosts")
    def hosts(@PathParam("id") id: UUID) = {
        new TunnelZoneHostResource(id, backend, uriInfo)
    }
}
