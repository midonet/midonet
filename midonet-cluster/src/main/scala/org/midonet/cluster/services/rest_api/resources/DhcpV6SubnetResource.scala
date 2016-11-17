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

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api.annotation.AllowCreate
import org.midonet.cluster.rest_api.models.{Bridge, DhcpSubnet6}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkNoContentResponse, ResourceContext}
import org.midonet.packets.IPv6Subnet

@RequestScoped
@AllowCreate(Array(APPLICATION_DHCPV6_SUBNET_JSON,
                   APPLICATION_JSON))
class DhcpV6SubnetResource @Inject()(bridgeId: UUID, resContext: ResourceContext)
    extends MidonetResource[DhcpSubnet6](resContext) {


    @GET
    @Path("{subnetAddress}")
    @Produces(Array(APPLICATION_DHCPV6_SUBNET_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("subnetAddress") subnetAddress: String,
                     @HeaderParam("Accept") accept: String): DhcpSubnet6 = {
        getSubnet(IPv6Subnet.fromUriCidr(subnetAddress))
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Produces(Array(APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[DhcpSubnet6] = {
        val bridge = getResource(classOf[Bridge], bridgeId)
        listResources(classOf[DhcpSubnet6], bridge.dhcpv6Ids.asScala).asJava
    }

    private def subnetNotFoundResp(subnetAddr: String): Response =
        buildErrorResponse(Status.NOT_FOUND,
                           getMessage(NETWORK_SUBNET_NOT_FOUND, bridgeId,
                                      subnetAddr))

    @PUT
    @Path("{subnetAddress}")
    @Consumes(Array(APPLICATION_DHCPV6_SUBNET_JSON,
                    APPLICATION_JSON))
    override def update(@PathParam("subnetAddress") subnetAddress: String,
                        subnet: DhcpSubnet6,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = tryTx { tx =>
        getSubnet(IPv6Subnet.fromUriCidr(subnetAddress), tx).map(current => {
            subnet.update(current)
            tx.update(subnet)
            OkNoContentResponse
        }).getOrElse(subnetNotFoundResp(subnetAddress))
    }

    @DELETE
    @Path("{subnetAddress}")
    override def delete(@PathParam("subnetAddress") subnetAddress: String)
    : Response = tryTx { tx =>
        getSubnet(IPv6Subnet.fromUriCidr(subnetAddress), tx).map(subnet => {
            tx.delete(classOf[DhcpSubnet6], subnet.id)
            OkNoContentResponse
        }).getOrElse(subnetNotFoundResp(subnetAddress))
    }

    @Path("{subnetAddress}/hostsV6")
    def hosts(@PathParam("subnetAddress") subnetAddress: String)
    : DhcpV6HostResource = {
        new DhcpV6HostResource(bridgeId, IPv6Subnet.fromUriCidr(subnetAddress),
                               resContext)
    }

    protected override def createFilter(subnet: DhcpSubnet6,
                                        tx: ResourceTransaction): Unit = {
        subnet.create(bridgeId)
        tx.create(subnet)
    }

    private def getSubnet(subnetAddress: IPv6Subnet, tx: ResourceTransaction = null)
    : Option[DhcpSubnet6] = {
        if (tx eq null) {
            val bridge = getResource(classOf[Bridge], bridgeId)
            listResources(classOf[DhcpSubnet6], bridge.dhcpv6Ids.asScala)
                .find(_.subnetAddress == subnetAddress)
        } else {
            val bridge = tx.get(classOf[Bridge], bridgeId)
            tx.list(classOf[DhcpSubnet6], bridge.dhcpv6Ids.asScala)
                .find(_.subnetAddress == subnetAddress)
        }
    }

}
