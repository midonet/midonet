/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse
import org.midonet.cluster.rest_api.models.{Bridge, DhcpHost, DhcpSubnet}
import org.midonet.cluster.rest_api.validation.MessageProperty.{NETWORK_SUBNET_NOT_FOUND, SUBNET_HAS_HOST, getMessage}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.{IPv4Subnet, MAC}

@RequestScoped
class DhcpHostResource @Inject()(bridgeId: UUID, subnetAddress: IPv4Subnet,
                                 resContext: ResourceContext)
    extends MidonetResource[DhcpHost](resContext) {

    @GET
    @Path("/{mac}")
    @Produces(Array(APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_JSON))
    override def get(@PathParam("mac") mac: String,
                     @HeaderParam("Accept") accept: String): DhcpHost = {
        val m = MAC.fromString(mac)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            val host = subnet.dhcpHosts.asScala.find(host => {
                MAC.fromString(host.macAddr) == m
            })
            host.foreach(_.setBaseUri(subnet.getUri))
            host
        }))
            .getOrThrow
            .getOrElse {
                throw new NotFoundHttpException(
                    getMessage(NETWORK_SUBNET_NOT_FOUND, bridgeId,
                               subnetAddress))
            }
    }

    @GET
    @Produces(Array(APPLICATION_DHCP_HOST_COLLECTION_JSON_V2))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[DhcpHost] = {
        getSubnet(subnetAddress).map(_.map(subnet => {
            val hosts = subnet.dhcpHosts.asScala
            hosts.foreach(_.setBaseUri(subnet.getUri))
            hosts.asJava
        }))
        .getOrThrow
        .getOrElse {
            throw new NotFoundHttpException(
                getMessage(NETWORK_SUBNET_NOT_FOUND, bridgeId, subnetAddress))
        }
    }

    @POST
    @Consumes(Array(APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_JSON))
    override def create(host: DhcpHost,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        val subnet = getSubnet(subnetAddress).getOrThrow.getOrElse {
            return subnetNotFoundResp
        }

        val hostMac = MAC.fromString(host.macAddr)
        if (subnet.dhcpHosts.asScala.exists(
            h => MAC.fromString(h.macAddr) == hostMac)) {
            val msg = getMessage(SUBNET_HAS_HOST, subnetAddress,
                                 bridgeId, host.macAddr)
            buildErrorResponse(Status.CONFLICT, msg)
        } else {
            host.setBaseUri(subnet.getUri)
            subnet.dhcpHosts.add(host)
            updateResource(subnet, Response.created(host.getUri).build())
        }
    }

    private def subnetNotFoundResp: Response =
        buildErrorResponse(Status.NOT_FOUND,
                           getMessage(NETWORK_SUBNET_NOT_FOUND,
                                      bridgeId, subnetAddress))

    @PUT
    @Path("/{mac}")
    @Consumes(Array(APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_JSON))
    override def update(@PathParam("mac") mac: String, host: DhcpHost,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        val m = MAC.fromString(mac)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            subnet.dhcpHosts.asScala.find(h => { MAC.fromString(h.macAddr) == m })
                .map(h => {
                    val index = subnet.dhcpHosts.indexOf(h)
                    host.setBaseUri(subnet.getUri)
                    subnet.dhcpHosts.remove(index)
                    subnet.dhcpHosts.add(index, host)
                    updateResource(subnet)
                })
        }))
            .getOrThrow
            .getOrElse(subnetNotFoundResp)
    }

    @DELETE
    @Path("/{mac}")
    override def delete(@PathParam("mac") mac: String): Response = {
        val m = MAC.fromString(mac)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            subnet.dhcpHosts.asScala.find(h => { MAC.fromString(h.macAddr) == m })
                .map(h => {
                    subnet.dhcpHosts.remove(h)
                    updateResource(subnet)
                })

        }))
            .getOrThrow
            .getOrElse(subnetNotFoundResp)
    }

    private def getSubnet(subnetAddress: IPv4Subnet)
    : Future[Option[DhcpSubnet]] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet],
                                             bridge.dhcpIds.asScala))
            .map(_.find(_.subnetAddress == subnetAddress))
    }

}
