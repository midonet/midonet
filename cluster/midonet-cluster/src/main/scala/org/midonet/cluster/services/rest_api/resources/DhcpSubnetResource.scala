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

import org.midonet.cluster.rest_api.annotation.AllowCreate
import org.midonet.cluster.rest_api.models.{Bridge, DhcpSubnet}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.IPv4Subnet

@RequestScoped
@AllowCreate(Array(APPLICATION_DHCP_SUBNET_JSON,
                   APPLICATION_DHCP_SUBNET_JSON_V2,
                   APPLICATION_JSON))
class DhcpSubnetResource @Inject()(bridgeId: UUID, resContext: ResourceContext)
    extends MidonetResource[DhcpSubnet](resContext) {


    @GET
    @Path("{subnetAddress}")
    @Produces(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    override def get(@PathParam("subnetAddress") subnetAddress: String,
                     @HeaderParam("Accept") accept: String): DhcpSubnet = {
        getSubnet(IPv4Subnet.fromZkString(subnetAddress))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Produces(Array(APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
                    APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[DhcpSubnet] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet],
                                             bridge.dhcpIds.asScala))
            .getOrThrow
            .asJava
    }

    @PUT
    @Path("{subnetAddress}")
    @Consumes(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_JSON))
    override def update(@PathParam("subnetAddress") subnetAddress: String,
                        subnet: DhcpSubnet,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        getSubnet(IPv4Subnet.fromZkString(subnetAddress)).map(_.map(current => {
            subnet.update(current)
            updateResource(subnet)
        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    @DELETE
    @Path("{subnetAddress}")
    override def delete(@PathParam("subnetAddress") subnetAddress: String)
    : Response = {
        getSubnet(IPv4Subnet.fromZkString(subnetAddress)).map(_.map(subnet => {
            deleteResource(classOf[DhcpSubnet], subnet.id)
        }))
            .getOrThrow
            .getOrElse(Response.status(Status.NOT_FOUND).build())
    }

    @Path("{subnetAddress}/hosts")
    def hosts(@PathParam("subnetAddress") subnetAddress: IPv4Subnet)
    : DhcpHostResource = {
        new DhcpHostResource(bridgeId, subnetAddress, resContext)
    }

    protected override def createFilter = (subnet: DhcpSubnet) => {
        subnet.create(bridgeId)
    }

    private def getSubnet(subnetAddress: IPv4Subnet)
    : Future[Option[DhcpSubnet]] = {
        getResource(classOf[Bridge], bridgeId)
            .flatMap(bridge => listResources(classOf[DhcpSubnet],
                                             bridge.dhcpIds.asScala))
            .map(_.find(_.subnetAddress == subnetAddress))
    }

}
