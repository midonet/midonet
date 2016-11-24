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
import org.midonet.cluster.rest_api.ConflictHttpException
import org.midonet.cluster.rest_api.annotation.AllowCreate
import org.midonet.cluster.rest_api.models.{Bridge, DhcpSubnet}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkNoContentResponse, ResourceContext}
import org.midonet.packets.IPv4Subnet

@RequestScoped
@AllowCreate(Array(APPLICATION_DHCP_SUBNET_JSON_V2,
                   APPLICATION_JSON))
class DhcpSubnetResource @Inject()(bridgeId: UUID, resContext: ResourceContext)
    extends MidonetResource[DhcpSubnet](resContext) {


    @GET
    @Path("{subnetAddress}")
    @Produces(Array(APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    override def get(@PathParam("subnetAddress") subnetAddress: String,
                     @HeaderParam("Accept") accept: String): DhcpSubnet = {
        getSubnet(IPv4Subnet.fromUriCidr(subnetAddress))
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Produces(Array(APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[DhcpSubnet] = {
        val bridge = getResource(classOf[Bridge], bridgeId)
        listResources(classOf[DhcpSubnet], bridge.dhcpIds.asScala).asJava
    }

    private def subnetNotFoundResp(subnetAddr: String): Response =
        buildErrorResponse(Status.NOT_FOUND,
                           getMessage(NETWORK_SUBNET_NOT_FOUND, bridgeId,
                                      subnetAddr))

    @PUT
    @Path("{subnetAddress}")
    @Consumes(Array(APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    override def update(@PathParam("subnetAddress") subnetAddress: String,
                        subnet: DhcpSubnet,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = tryTx { tx =>
        getSubnet(IPv4Subnet.fromUriCidr(subnetAddress), tx).map(current => {
            subnet.update(current)
            tx.update(subnet)
            OkNoContentResponse
        }).getOrElse(subnetNotFoundResp(subnetAddress))
    }

    @DELETE
    @Path("{subnetAddress}")
    override def delete(@PathParam("subnetAddress") subnetAddress: String)
    : Response = tryTx { tx =>
        getSubnet(IPv4Subnet.fromUriCidr(subnetAddress), tx).map(subnet => {
            tx.delete(classOf[DhcpSubnet], subnet.id)
            OkNoContentResponse
        }).getOrElse(subnetNotFoundResp(subnetAddress))
    }

    @Path("{subnetAddress}/hosts")
    def hosts(@PathParam("subnetAddress") subnetAddress: String)
    : DhcpHostResource = {
        new DhcpHostResource(bridgeId, IPv4Subnet.fromUriCidr(subnetAddress),
                             resContext)
    }

    protected override def createFilter(subnet: DhcpSubnet,
                                        tx: ResourceTransaction): Unit = {
        subnet.create(bridgeId)
        val subnetAddress = subnet.subnetAddress.asInstanceOf[IPv4Subnet]
        getSubnet(subnetAddress, tx) match {
            case Some(_) => throw new ConflictHttpException(
                getMessage(BRIDGE_DHCP_HAS_SUBNET, subnetAddress))
            case None =>
                tx.create(subnet)
        }
    }

    private def getSubnet(subnetAddress: IPv4Subnet, tx: ResourceTransaction = null)
    : Option[DhcpSubnet] = {
        if (tx eq null) {
            val bridge = getResource(classOf[Bridge], bridgeId)
            listResources(classOf[DhcpSubnet], bridge.dhcpIds.asScala)
                .find(_.subnetAddress == subnetAddress)
        } else {
            val bridge = tx.get(classOf[Bridge], bridgeId)
            tx.list(classOf[DhcpSubnet], bridge.dhcpIds.asScala)
                .find(_.subnetAddress == subnetAddress)
        }
    }

}
