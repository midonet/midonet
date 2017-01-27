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

import javax.ws.rs.core.MediaType._
import javax.ws.rs.core.Response
import javax.ws.rs.{Consumes, DELETE, _}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.util.control.NonFatal

import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.rest_api.models.Ip4MacPair
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.packets.{IPv4Addr, MAC}

@RequestScoped
class BridgeArpTableResource(bridgeId: UUID, resourceContext: ResourceContext)
    extends MidonetResource[Ip4MacPair](resourceContext) {

    @GET
    @Path("{ip_mac}")
    @Produces(Array(APPLICATION_IP4_MAC_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("ip_mac") ipMac: String,
                     @HeaderParam("Accept") accept: String): Ip4MacPair = {
        val (address, mac) = parseIpMac(ipMac)

        tryLegacyRead {
            val inArpTable = Await.result(arpTable.containsRemote(address, mac),
                                          requestTimeout)

            if (inArpTable) {
                new Ip4MacPair(resourceContext.uriInfo.getBaseUri, bridgeId,
                               address.toString, mac.toString)
            } else {
                throw new NotFoundHttpException(getMessage(ARP_ENTRY_NOT_FOUND))
            }
        }
    }

    @GET
    @Produces(Array(APPLICATION_IP4_MAC_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : util.List[Ip4MacPair] = {
        val entries = tryLegacyRead {
            Await.result(arpTable.remoteSnapshot, requestTimeout)
        }
        val result = for ((ip, mac) <- entries.toList) yield
            new Ip4MacPair(resourceContext.uriInfo.getBaseUri, bridgeId,
                           ip.toString, mac.toString)
        result.asJava
    }

    @POST
    @Consumes(Array(APPLICATION_IP4_MAC_JSON))
    override def create(ipMac: Ip4MacPair,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        throwIfViolationsOn(ipMac)

        ipMac.bridgeId = bridgeId
        ipMac.setBaseUri(resourceContext.uriInfo.getBaseUri)

        val address =
            try IPv4Addr.fromString(ipMac.ip)
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID))
            }

        val mac =
            try MAC.fromString(ipMac.mac)
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID))
            }

        tryLegacyWrite {
            Await.result(arpTable.addPersistent(address, mac), requestTimeout)
            Response.created(ipMac.getUri).build()
        }
    }

    @DELETE
    @Path("{ip_mac}")
    override def delete(@PathParam("ip_mac") ipMac: String): Response = {
        val (address, mac) = parseIpMac(ipMac)

        tryLegacyWrite {
            Await.result(arpTable.removePersistent(address, mac), requestTimeout)
            Response.noContent().build()
        }
    }

    private def arpTable: StateTable[IPv4Addr, MAC] = {
        stateTableStore.bridgeArpTable(bridgeId)
    }

    private def parseIpMac(pair: String): (IPv4Addr, MAC) = {
        val tokens = pair.split("_")
        val address =
            try IPv4Addr.fromString(tokens(0))
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID))
            }
        val mac =
            try MAC.fromString(tokens(1).replace('-', ':'))
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID))
            }
        (address, mac)
    }

}
