package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs._

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject

import org.midonet.cluster.rest_api.models.{Bridge, DhcpHost, DhcpSubnet}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.packets.{MAC, IPv4Subnet}

class DhcpHostResource @Inject()(bridgeId: UUID, subnetAddress: IPv4Subnet,
                                 backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_DHCP_HOST_COLLECTION_JSON,
                    APPLICATION_DHCP_HOST_COLLECTION_JSON_V2))
    def list(): JList[DhcpHost] = {
        getSubnet(subnetAddress).map(_.map(subnet => {
            val hosts = subnet.hosts.asScala
            hosts.foreach(_.setBaseUri(subnet.getUri))
            hosts.asJava
        }))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Path("/{mac}")
    @Produces(Array(APPLICATION_DHCP_HOST_JSON,
                    APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_JSON))
    def get(@PathParam("mac") mac: String): DhcpHost = {
        val m = MAC.fromString(mac)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            val host = subnet.hosts.asScala.find(host => {
                MAC.fromString(host.macAddr) == m
            })
            host.foreach(_.setBaseUri(subnet.getUri))
            host
        }))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @POST
    @Consumes(Array(APPLICATION_DHCP_HOST_JSON,
                    APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_JSON))
    def create(host: DhcpHost): Response = {
        getSubnet(subnetAddress).map(_.map(subnet => {
            if (subnet.hosts.asScala.find(h => {
                MAC.fromString(h.macAddr) == MAC.fromString(host.macAddr)
            }).nonEmpty) {
                Response.status(Status.CONFLICT).build()
            } else {
                host.setBaseUri(subnet.getUri)
                subnet.hosts.add(host)
                updateResource(subnet, Response.created(host.getUri).build())
            }
        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    @PUT
    @Path("/{mac}")
    @Consumes(Array(APPLICATION_DHCP_HOST_JSON,
                    APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_JSON))
    def update(@PathParam("mac") mac: String, host: DhcpHost): Response = {
        val m = MAC.fromString(mac)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            subnet.hosts.asScala.find(h => { MAC.fromString(h.macAddr) == m })
                .map(h => {
                    val index = subnet.hosts.indexOf(h)
                    host.setBaseUri(subnet.getUri)
                    subnet.hosts.remove(index)
                    subnet.hosts.add(index, host)
                    updateResource(subnet)
                })
        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    @DELETE
    @Path("/{mac}")
    def delete(@PathParam("mac") mac: String): Response = {
        val m = MAC.fromString(mac)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            subnet.hosts.asScala.find(h => { MAC.fromString(h.macAddr) == m })
                .map(h => {
                    subnet.hosts.remove(h)
                    updateResource(subnet)
                })

        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    private def getSubnet(subnetAddress: IPv4Subnet)
    : Future[Option[DhcpSubnet]] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet],
                                             bridge.getDhcpIds.asScala))
            .map(_.find(_.subnetAddress == subnetAddress))
    }

}
