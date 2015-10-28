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

import java.util
import java.util.UUID
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs.{Path, _}

import scala.collection.JavaConversions._
import scala.concurrent.Await

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.IPAddrGroup.IPAddrPorts
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{IpAddrGroup, IpAddrGroupAddr, Ipv4AddrGroupAddr, Ipv6AddrGroupAddr}
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.util.IPAddressUtil.{toIPAddr, toProto}
import org.midonet.packets.IPAddr.canonicalize
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr}

@ApiResource(version = 1, name = "ipAddrGroups", template = "ipAddrGroupTemplate")
@Path("ip_addr_groups")
@RequestScoped
@AllowGet(Array(APPLICATION_IP_ADDR_GROUP_JSON))
@AllowList(Array(APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON))
@AllowCreate(Array(APPLICATION_IP_ADDR_GROUP_JSON))
@AllowDelete
class IpAddrGroupResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[IpAddrGroup](resContext){

    @Path("{id}/ip_addrs")
    def ports(@PathParam("id") id: UUID): IpAddrGroupAddrResource = {
        new IpAddrGroupAddrResource(id, resContext)
    }

    @Path("/{id}/versions/{version}/ip_addrs")
    def getIpAddrGroupAddrVersionResource(@PathParam("id") id: UUID,
                                          @PathParam("version") version: Int)
    :IpAddrGroupAddrVersionResource = {
        if (version != 4 && version != 6) {
            throw new BadRequestHttpException("Invalid IP version: " + version)
        }
      new IpAddrGroupAddrVersionResource(id, resContext)
    }

}

sealed trait IpAddrGroupAddrSubResource {

    protected[this] val store: Storage
    protected[this] val ipAddrGroupId: UUID
    protected[this] val uriInfo: UriInfo

    protected[resources] def ipg = try {
        Await.result(
            store.get(classOf[Topology.IPAddrGroup], ipAddrGroupId),
            MidonetResource.Timeout)
    } catch {
        case e: NotFoundException =>
            throw new NotFoundHttpException("IP addr group not found: " +
                                            ipAddrGroupId)
    }

    protected[this] def extractAddresses(from: Topology.IPAddrGroup.IPAddrPorts)
    : IpAddrGroupAddr = {
        val res = toIpGroupAddr(toIPAddr(from.getIpAddress))
        res.setBaseUri(uriInfo.getBaseUri)
        res.ipAddrGroupId = ipAddrGroupId
        res
    }

    protected[this] def toIpGroupAddr(ip: IPAddr): IpAddrGroupAddr = ip match {
        case ip4: IPv4Addr =>
            new Ipv4AddrGroupAddr(uriInfo.getBaseUri, ipAddrGroupId, ip4)
        case ip6: IPv6Addr =>
            new Ipv6AddrGroupAddr(uriInfo.getBaseUri, ipAddrGroupId, ip6)
    }

}

@RequestScoped
class IpAddrGroupAddrVersionResource @Inject()(
          protected[this] val ipAddrGroupId: UUID, resContext: ResourceContext)
extends IpAddrGroupAddrSubResource {

    protected[this] val store = resContext.backend.store
    protected[this] val uriInfo = resContext.uriInfo

    protected final implicit val log =
        Logger(LoggerFactory.getLogger(getClass))

    @GET
    @Path("{addr}")
    @Produces(Array(APPLICATION_IP_ADDR_GROUP_ADDR_JSON))
    def get(@PathParam("addr") addr: String): IpAddrGroupAddr = {
        val canonicalAddr = IPAddr.canonicalize(addr)
        ipg.getIpAddrPortsList.map(extractAddresses).find { add =>
            add.getAddr == canonicalAddr
        } match {
            case None =>
                throw new NotFoundHttpException("The IP address group does " +
                                                "not contain the specified " +
                                                "address " + addr)
            case Some(_) =>
                toIpGroupAddr(IPAddr.fromString(canonicalize(addr)))
        }
    }

    @DELETE
    @Path("{addr}")
    def delete(@PathParam("addr")addr: String): Response = {
        val canonicalAddr = IPAddr.canonicalize(addr)
        val oldIpg = ipg
        val oldList = oldIpg.getIpAddrPortsList
        val newList =
            new util.ArrayList[Topology.IPAddrGroup.IPAddrPorts](oldList.size)
        var doUpdate = false
        oldList.foreach { a =>
            if (toIPAddr(a.getIpAddress).toString == canonicalAddr) {
                doUpdate = true
            } else {
                newList.add(a)
            }
        }
        MidonetResource.tryWrite {
            if (doUpdate) {
                store.update(ipg.toBuilder.clearIpAddrPorts()
                                          .addAllIpAddrPorts(newList)
                                          .build())
            }
            MidonetResource.OkNoContentResponse
        }

    }
}

@RequestScoped
class IpAddrGroupAddrResource @Inject()(protected[this] val ipAddrGroupId: UUID,
                                        resContext: ResourceContext)
    extends IpAddrGroupAddrSubResource {

    protected[this] val store = resContext.backend.store
    protected[this] val uriInfo = resContext.uriInfo

    @GET
    @Produces(Array(APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON))
    def list(): util.List[IpAddrGroupAddr] = {
        ipg.getIpAddrPortsList.map(extractAddresses).toList
    }

    @POST
    @Consumes(Array(APPLICATION_IP_ADDR_GROUP_ADDR_JSON))
    def create(addr: IpAddrGroupAddr): Response = {

        resContext.validator.validate(addr)
        if (addr.getVersion != 4 && addr.getVersion != 6) {
            throw new BadRequestHttpException(
                "Invalid IP version: " + addr.getVersion)
        }

        val canonicalAddr = try {
            IPAddr.fromString(IPAddr.canonicalize(addr.getAddr))
        } catch {
            case t: IllegalArgumentException =>
                throw new BadRequestHttpException(t.getMessage)
        }

        ipg.getIpAddrPortsList.find ( p =>
            toIPAddr(p.getIpAddress).toString == addr.getAddr
        ) match {
            case None =>
                store.update(ipg.toBuilder.addIpAddrPorts(
                    IPAddrPorts.newBuilder()
                        .setIpAddress(canonicalAddr)
                        .build()
                    ).build()
                )
            case Some(_) => // Ignore if the IP address group exists.
        }

        addr.setBaseUri(resContext.uriInfo.getBaseUri)
        MidonetResource.OkCreated(addr.getUri)
    }

}
