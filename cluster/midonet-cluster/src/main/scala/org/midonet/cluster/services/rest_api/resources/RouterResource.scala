package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Router
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class RouterResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_ROUTER_COLLECTION_JSON,
                    APPLICATION_ROUTER_COLLECTION_JSON_V2,
                    APPLICATION_JSON))
    def list(@QueryParam("tenant_id") tenantId: String): JList[Router] = {
        listResources(classOf[Router])
            .map(_.filter(tenantFilter(tenantId)).asJava)
            .getOrThrow
    }

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_ROUTER_JSON,
                    APPLICATION_ROUTER_JSON_V2,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): Router = {
        getResource(classOf[Router], id).getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_ROUTER_JSON,
                    APPLICATION_ROUTER_JSON_V2,
                    APPLICATION_JSON))
    def create(router: Router): Response = {
        router.create()
        createResource(router)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_ROUTER_JSON,
                    APPLICATION_ROUTER_JSON_V2,
                    APPLICATION_JSON))
    def update(@PathParam("id") id: UUID, router: Router): Response = {
        getResource(classOf[Router], id).map(current => {
            router.update(id, current)
            updateResource(router)
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        deleteResource(classOf[Router], id)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): PortResource = {
        new RouterPortResource(id, backend, uriInfo)
    }

    @Path("{id}/routes")
    def routes(@PathParam("id") id: UUID): RouterRouteResource = {
        new RouterRouteResource(id, backend, uriInfo)
    }

    protected def tenantFilter(tenantId: String): (Router) => Boolean = {
        if (tenantId eq null) (_: Router) => true
        else (r: Router) => r.tenantId == tenantId
    }

}
