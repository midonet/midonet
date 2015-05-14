package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject

import org.midonet.cluster.rest_api.models.{Bridge, DhcpSubnet}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.packets.IPv4Subnet

class DhcpSubnetResource @Inject()(bridgeId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
                    APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2))
    def list(): JList[DhcpSubnet] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet],
                                             bridge.getDhcpIds.asScala))
            .getOrThrow
            .asJava
    }

    @GET
    @Path("{subnetAddress}")
    @Produces(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    def get(@PathParam("subnetAddress") subnetAddress: IPv4Subnet): DhcpSubnet = {
        getSubnet(subnetAddress)
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @POST
    @Consumes(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    def create(subnet: DhcpSubnet): Response = {
        subnet.create(bridgeId)
        createResource(subnet)
    }

    @PUT
    @Path("{subnetAddress}")
    @Consumes(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    def update(@PathParam("subnetAddress") subnetAddress: IPv4Subnet,
               subnet: DhcpSubnet): Response = {
        getSubnet(subnetAddress).map(_.map(current => {
            subnet.update(subnetAddress, current)
            updateResource(subnet)
        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    @DELETE
    @Path("{subnetAddress}")
    def delete(@PathParam("subnetAddress") subnetAddress: IPv4Subnet)
    : Response = {
        getSubnet(subnetAddress).map(_.map(subnet => {
            deleteResource(classOf[DhcpSubnet], subnet.id)
        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    @Path("{subnetAddress}/hosts")
    def hosts(@PathParam("subnetAddress") subnetAddress: IPv4Subnet)
    : DhcpHostResource = {
        new DhcpHostResource(bridgeId, subnetAddress, backend, uriInfo)
    }

    private def getSubnet(subnetAddress: IPv4Subnet)
    : Future[Option[DhcpSubnet]] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet],
                                             bridge.getDhcpIds.asScala))
            .map(_.find(_.subnetAddress == subnetAddress))
    }

}
