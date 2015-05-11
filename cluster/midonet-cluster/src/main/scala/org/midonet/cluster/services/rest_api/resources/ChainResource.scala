package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject

import org.midonet.cluster.rest_api.models.Chain
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

class ChainResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_CHAIN_COLLECTION_JSON,
                    APPLICATION_JSON))
    def list(@QueryParam("tenant_id") tenantId: String): JList[Chain] = {
        listResources(classOf[Chain])
            .map(_.filter(tenantFilter(tenantId)).asJava)
            .getOrThrow

    }

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_CHAIN_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): Chain = {
        getResource(classOf[Chain], id).getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_CHAIN_JSON,
                    APPLICATION_JSON))
    def create(chain: Chain): Response = {
        chain.create()
        createResource(chain)
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        deleteResource(classOf[Chain], id)
    }

    @Path("{id}/rules")
    def rules(@PathParam("id") id: UUID): ChainRuleResource = {
        new ChainRuleResource(id, backend, uriInfo)
    }

    protected def tenantFilter(tenantId: String): (Chain) => Boolean = {
        if (tenantId eq null) (_: Chain) => true
        else (c: Chain) => c.tenantId == tenantId
    }

}
