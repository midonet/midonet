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

import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api.models.{Bridge, DhcpSubnet6, DhcpV6Host}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.IPv6Subnet

@RequestScoped
class DhcpV6HostResource @Inject()(bridgeId: UUID, subnetAddress: IPv6Subnet,
                                  resContext: ResourceContext)
    extends MidonetResource[DhcpV6Host](resContext) {

    @GET
    @Path("/{client_id}")
    @Produces(Array(APPLICATION_DHCPV6_HOST_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("client_id") clientId: String,
                     @HeaderParam("Accept") accept: String): DhcpV6Host = {
        val reqClientId = DhcpV6Host.clientIdFromUri(clientId)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            val host = subnet.dhcpHosts.asScala.find (_.clientId == reqClientId)
            host.foreach(_.setBaseUri(subnet.getUri))
            host
        }))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Produces(Array(APPLICATION_DHCPV6_HOST_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[DhcpV6Host] = {
        getSubnet(subnetAddress).map(_.map(subnet => {
            val hosts = subnet.dhcpHosts.asScala
            hosts.foreach(_.setBaseUri(subnet.getUri))
            hosts.asJava
        }))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    private def subnetNotFoundResp: Response =
        buildErrorResponse(Status.NOT_FOUND,
                           getMessage(NETWORK_SUBNET_NOT_FOUND, bridgeId,
                                      subnetAddress))

    @POST
    @Consumes(Array(APPLICATION_DHCPV6_HOST_JSON,
                    APPLICATION_JSON))
    override def create(host: DhcpV6Host,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        getSubnet(subnetAddress).map(_.map(subnet => {
            if (subnet.dhcpHosts.asScala.exists(_.clientId == host.clientId)) {
                val msg = getMessage(SUBNET_HAS_HOST, subnetAddress,
                                     bridgeId, host.fixedAddress)
                buildErrorResponse(Status.CONFLICT, msg)
            } else {
                host.setBaseUri(subnet.getUri)
                subnet.dhcpHosts.add(host)
                updateResource(subnet, Response.created(host.getUri).build())
            }
        }))
            .getOrThrow
            .getOrElse(subnetNotFoundResp)
    }

    @PUT
    @Path("/{client_id}")
    @Consumes(Array(APPLICATION_DHCPV6_HOST_JSON,
                    APPLICATION_JSON))
    override def update(@PathParam("client_id") clientId: String,
                        host: DhcpV6Host,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        val reqClientId = DhcpV6Host.clientIdFromUri(clientId)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            subnet.dhcpHosts.asScala.find( _.clientId == reqClientId )
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
    @Path("/{client_id}")
    override def delete(@PathParam("client_id") clientId: String): Response = {
        val reqClientId = DhcpV6Host.clientIdFromUri(clientId)
        getSubnet(subnetAddress).map(_.flatMap(subnet => {
            subnet.dhcpHosts.asScala.find( _.clientId == reqClientId)
                                    .map(h => {
                                        subnet.dhcpHosts.remove(h)
                                        updateResource(subnet)
                                    })

        }))
            .getOrThrow
            .getOrElse(subnetNotFoundResp)
    }

    private def getSubnet(subnetAddress: IPv6Subnet)
    : Future[Option[DhcpSubnet6]] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet6],
                                             bridge.dhcpv6Ids.asScala))
            .map(_.find(_.subnetAddress == subnetAddress))
    }

}
