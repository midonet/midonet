package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Bridge
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class BridgeResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_BRIDGE_COLLECTION_JSON,
                    APPLICATION_BRIDGE_COLLECTION_JSON_V2,
                    APPLICATION_BRIDGE_COLLECTION_JSON_V3,
                    APPLICATION_JSON))
    def list(@QueryParam("tenant_id") tenantId: String): JList[Bridge] = {
        listResources(classOf[Bridge])
            .map(_.filter(tenantFilter(tenantId)).asJava)
            .getOrThrow
    }

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_BRIDGE_JSON,
                    APPLICATION_BRIDGE_JSON_V2,
                    APPLICATION_BRIDGE_JSON_V3,
                    APPLICATION_JSON))
    def get(@PathParam("id") id: UUID): Bridge = {
        getResource(classOf[Bridge], id).getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_BRIDGE_JSON,
                    APPLICATION_BRIDGE_JSON_V2,
                    APPLICATION_BRIDGE_JSON_V3,
                    APPLICATION_JSON))
    def create(bridge: Bridge): Response = {
        bridge.create()
        createResource(bridge)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_BRIDGE_JSON,
                    APPLICATION_BRIDGE_JSON_V2,
                    APPLICATION_BRIDGE_JSON_V3,
                    APPLICATION_JSON))
    def update(@PathParam("id") id: UUID, bridge: Bridge): Response = {
        getResource(classOf[Bridge], id).map(current => {
            bridge.update(id, current)
            updateResource(bridge)
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        deleteResource(classOf[Bridge], id)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): PortResource = {
        new BridgePortResource(id, backend, uriInfo)
    }

    @Path("{id}/dhcp")
    def dhcps(@PathParam("id") id: UUID): DhcpSubnetResource = {
        new DhcpSubnetResource(id, backend, uriInfo)
    }

    protected def tenantFilter(tenantId: String): (Bridge) => Boolean = {
        if (tenantId eq null) (_: Bridge) => true
        else (b: Bridge) => b.getTenantId == tenantId
    }

}
