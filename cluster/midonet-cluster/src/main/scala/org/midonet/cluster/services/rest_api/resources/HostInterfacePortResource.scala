package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status.NOT_FOUND
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject

import org.midonet.cluster.rest_api.models.{Port, Host, HostInterfacePort}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

class HostInterfacePortResource @Inject()(hostId: UUID, backend: MidonetBackend,
                                          uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON))
    def list: JList[HostInterfacePort] = {
        getResource(classOf[Host], hostId)
            .flatMap(host => listResources(classOf[HostInterfacePort],
                                           host.portIds.asScala))
            .getOrThrow
            .asJava
    }

    @GET
    @Path("{portId}")
    def get(@PathParam("portId") portId: UUID): HostInterfacePort = {
        getResource(classOf[Host], hostId)
            .flatMap(_.portIds.asScala
                         .find(_ == portId)
                         .map(getResource(classOf[HostInterfacePort], _))
                         .getOrElse(throw new WebApplicationException(NOT_FOUND)))
            .getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_HOST_INTERFACE_PORT_JSON,
                    APPLICATION_JSON))
    def create(binding: HostInterfacePort): Response = {
        getResource(classOf[Port], binding.portId).map(port => {
            binding.setBaseUri(uriInfo.getBaseUri)
            binding.create(hostId)
            port.hostId = hostId
            port.interfaceName = binding.interfaceName
            updateResource(port, Response.created(binding.getUri).build())
        }).getOrThrow
    }

    @DELETE
    @Path("{portId}")
    def delete(@PathParam("portId") portId: UUID): Response = {
        getResource(classOf[Port], portId).map(port => {
            port.hostId = null
            port.interfaceName = null
            updateResource(port)
        }).getOrThrow
    }

}
