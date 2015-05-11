package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Host
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class HostResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_HOST_COLLECTION_JSON_V2,
                    APPLICATION_HOST_COLLECTION_JSON_V3,
                    APPLICATION_JSON))
    def list(): JList[Host] = {
        listResources(classOf[Host]).getOrThrow.map(setAlive).asJava
    }

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_HOST_JSON_V2,
                    APPLICATION_HOST_JSON_V3,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): Host = {
        val host = getResource(classOf[Host], id).getOrThrow
        setAlive(host)
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        if (isAlive(id)) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        val host = getResource(classOf[Host], id).getOrThrow
        if ((host.portIds ne null) && !host.portIds.isEmpty) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        deleteResource(classOf[Host], id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_HOST_JSON_V2,
                    APPLICATION_HOST_JSON_V3,
                    APPLICATION_JSON))
    def update(@PathParam("id") id: UUID, host: Host): Response = {
        if (host.floodingProxyWeight eq null) {
            return Response.status(Response.Status.BAD_REQUEST).build()
        }
        getResource(classOf[Host], id).map(current => {
            current.floodingProxyWeight = host.floodingProxyWeight
            updateResource(current)
        }).getOrThrow
    }

    @Path("{id}/interfaces")
    def interfaces(@PathParam("id") hostId: UUID): InterfaceResource = {
        new InterfaceResource(hostId, backend, uriInfo)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") hostId: UUID): HostInterfacePortResource = {
        new HostInterfacePortResource(hostId, backend, uriInfo)
    }

    private def isAlive(id: UUID): Boolean = {
        getResourceOwners(classOf[Host], id).getOrThrow.nonEmpty
    }

    private def setAlive(host: Host): Host = {
        host.alive = isAlive(host.id)
        host
    }
}
