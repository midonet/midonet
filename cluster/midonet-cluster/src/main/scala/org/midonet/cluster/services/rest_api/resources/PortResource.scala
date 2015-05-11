package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{RouterPort, BridgePort, Link, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class PortResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo)  {

    @GET
    @Produces(Array(APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    def list(): JList[Port] = {
        listResources(classOf[Port])
            .map(_.asJava)
            .getOrThrow
    }

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): Port = {
        getResource(classOf[Port], id).getOrThrow
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_JSON))
    def update(@PathParam("id") id: UUID, port: Port): Response = {
        val current = getResource(classOf[Port], id).getOrThrow
        port.update(id, current)
        updateResource(port)
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        deleteResource(classOf[Port], id)
    }

    @POST
    @Path("{id}/link")
    @Consumes(Array(APPLICATION_PORT_LINK_JSON,
                    APPLICATION_JSON))
    def link(@PathParam("id") id: UUID, link: Link): Response = {
        val port = getResource(classOf[Port], id).getOrThrow
        port.peerId = link.peerId
        updateResource(port)
    }

    @DELETE
    @Path("{id}/link")
    def unlink(@PathParam("id") id: UUID): Response = {
        val port = getResource(classOf[Port], id).getOrThrow
        port.peerId = null
        updateResource(port)
    }

}

class BridgePortResource @Inject()(bridgeId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends PortResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(): JList[Port] = {
        listResources(classOf[Port])
            .map(_.filter(_.getDeviceId == bridgeId).asJava)
            .getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_JSON))
    def create(port: BridgePort): Response = {
        port.create(bridgeId)
        createResource(port)
    }

}

class RouterPortResource @Inject()(routerId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends PortResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(): JList[Port] = {
        listResources(classOf[Port])
            .map(_.filter(_.getDeviceId == routerId).asJava)
            .getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_JSON))
    def create(port: RouterPort): Response = {
        port.create(routerId)
        createResource(port)
    }

}
