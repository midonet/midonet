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

import java.util
import java.util.UUID

import javax.ws.rs.{Consumes, GET, POST, Produces}
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.{BadRequestHttpException, ConflictHttpException}
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

@RequestScoped
class IpAddrGroupAddrResource @Inject()(ipAddrGroupId: UUID,
                                        resContext: ResourceContext)
    extends MidonetResource[IpAddrGroupAddr](resContext) {

    @GET
    @Produces(Array(APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON))
    def list(): util.List[IpAddrGroupAddr] = {
        getResource(classOf[IpAddrGroup], ipAddrGroupId).ipAddrPorts.asScala
            .map(toAddrGroupAddr).asJava
    }

    @POST
    @Consumes(Array(APPLICATION_IP_ADDR_GROUP_ADDR_JSON))
    def create(addr: IpAddrGroupAddr): Response = tryTx { tx =>

        resContext.validator.validate(addr)
        if (addr.getVersion != 4 && addr.getVersion != 6) {
            throw new BadRequestHttpException("Invalid IP version: " +
                                              addr.getVersion)
        }

        val ipAddress =
            try IPAddr.fromString(addr.addr)
            catch {
                case e: IllegalArgumentException =>
                    throw new BadRequestHttpException(e.getMessage)
            }

        val ipAddrGroup = getResource(classOf[IpAddrGroup], ipAddrGroupId)

        if (!ipAddrGroup.ipAddrPorts.asScala.exists(_.ipAddress == ipAddress)) {
            ipAddrGroup.ipAddrPorts.add(new IpAddrPort(ipAddress))
            tx.update(ipAddrGroup)

            addr.create(ipAddrGroupId)
            addr.setBaseUri(uriInfo.getBaseUri)
            addr.ipAddrGroupId = ipAddrGroupId
            MidonetResource.OkCreated(addr.getUri)
        } else {
            throw new ConflictHttpException(
                getMessage(IP_ADDR_GROUP_HAS_IP, ipAddress))
        }
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
