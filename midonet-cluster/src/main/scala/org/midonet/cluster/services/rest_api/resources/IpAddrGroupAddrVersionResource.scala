/*
 * Copyright 2016 Midokura SARL
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

import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

@RequestScoped
class IpAddrGroupAddrVersionResource @Inject()(ipAddrGroupId: UUID,
                                               resContext: ResourceContext)
    extends MidonetResource[IpAddrGroupAddr](resContext) {

    @GET
    @Path("{addr}")
    @Produces(Array(APPLICATION_IP_ADDR_GROUP_ADDR_JSON))
    override def get(@PathParam("addr") addr: String,
                     @HeaderParam("Accept") accept: String): IpAddrGroupAddr = {
        val ipAddress = IPAddr.fromString(addr)
        val ipAddrGroup = getResource(classOf[IpAddrGroup], ipAddrGroupId)
        ipAddrGroup.ipAddrPorts.asScala.find(_.ipAddress == ipAddress) match {
            case None =>
                throw new NotFoundHttpException("The IP address group does " +
                                                "not contain the specified " +
                                                "address " + addr)
            case Some(ipAddrPort) =>
                toAddrGroupAddr(ipAddrPort)
        }
    }

    @DELETE
    @Path("{addr}")
    override def delete(@PathParam("addr") addr: String): Response = tryTx { tx =>
        val ipAddress = IPAddr.fromString(addr)
        val ipAddrGroup = getResource(classOf[IpAddrGroup], ipAddrGroupId)

        val iterator = ipAddrGroup.ipAddrPorts.iterator()
        while (iterator.hasNext) {
            val ipAddrPort = iterator.next()
            if (ipAddrPort.ipAddress == ipAddress) {
                iterator.remove()
            }
        }
        tx.update(ipAddrGroup)
        MidonetResource.OkNoContentResponse
    }

    private def toAddrGroupAddr(from: IpAddrPort): IpAddrGroupAddr = {
        from.ipAddress match {
            case ip4: IPv4Addr =>
                new Ipv4AddrGroupAddr(uriInfo.getBaseUri, ipAddrGroupId, ip4)
            case ip6: IPv6Addr =>
                new Ipv6AddrGroupAddr(uriInfo.getBaseUri, ipAddrGroupId, ip6)
        }
    }
}
