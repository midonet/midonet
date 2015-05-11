package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Route
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class RouteResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_ROUTE_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): Route = {
        getResource(classOf[Route], id).getOrThrow
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        deleteResource(classOf[Route], id)
    }

}

@RequestScoped
class RouterRouteResource @Inject()(routerId: UUID, backend: MidonetBackend,
                                    uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_ROUTE_COLLECTION_JSON,
                    APPLICATION_JSON))
    def list(): JList[Route] = {
        listResources(classOf[Route])
            .map(_.filter(_.routerId == routerId).asJava)
            .getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_ROUTE_JSON,
                    APPLICATION_JSON))
    def create(route: Route): Response = {
        route.create(routerId)
        createResource(route)
    }

}