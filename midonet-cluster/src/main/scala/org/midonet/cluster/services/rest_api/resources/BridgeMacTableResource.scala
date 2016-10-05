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

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.concurrent.Await
import scala.util.control.NonFatal

import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.rest_api.models.{Bridge, BridgePort, MacPort}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.packets.MAC

@RequestScoped
class BridgeMacTableResource(bridgeId: UUID, vlanOption: Option[Short],
                             resourceContext: ResourceContext)
    extends MidonetResource[MacPort](resourceContext) {

    @GET
    @Path("{mac_port}")
    @Produces(Array(APPLICATION_MAC_PORT_JSON_V2,
                    APPLICATION_JSON))
    override def get(@PathParam("mac_port") macPort: String,
                      @HeaderParam("Accept") accept: String): MacPort = {
        val (mac, portId) = parseMacPort(macPort)
        val vlanId = vlanOption.getOrElse(Bridge.UNTAGGED_VLAN_ID)

        validateVlanId(vlanId)

        tryLegacyRead {
            if (Await.result(macTable(vlanId).containsRemote(mac, portId),
                             requestTimeout)) {
                new MacPort(resourceContext.uriInfo.getBaseUri, bridgeId,
                            mac.toString, portId, vlanId)
            } else {
                throw new NotFoundHttpException(getMessage(BRIDGE_HAS_MAC_PORT))
            }
        }
    }

    @GET
    @Produces(Array(APPLICATION_MAC_PORT_COLLECTION_JSON_V2,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : util.List[MacPort] = {

        val result = new util.ArrayList[MacPort]()
        vlanOption match {
            case Some(vlanId) =>
                validateVlanId(vlanId)
                addVlanEntries(vlanId, result)
            case None =>
                for (vlanId <- bridgeVlanIds) {
                    addVlanEntries(vlanId, result)
                }
        }
        result
    }

    private def addVlanEntries(vlanId: Short, list: util.List[MacPort]): Unit = {
        val entries = tryLegacyRead {
            Await.result(macTable(vlanId).remoteSnapshot, requestTimeout)
        }
        for ((mac, portId) <- entries.toList) {
            list add new MacPort(resourceContext.uriInfo.getBaseUri, bridgeId,
                                 mac.toString, portId, vlanId)
        }
    }

    @POST
    @Consumes(Array(APPLICATION_MAC_PORT_JSON_V2,
                    APPLICATION_JSON))
    override def create(macPort: MacPort,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        throwIfViolationsOn(macPort)

        macPort.bridgeId = bridgeId
        macPort.vlanId = vlanOption.getOrElse[Short] {
            if (macPort.vlanId ne null) macPort.vlanId else Bridge.UNTAGGED_VLAN_ID
        }

        val mac =
            try MAC.fromString(macPort.macAddr)
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID))
            }

        val port = getResource(classOf[BridgePort], macPort.portId)
        if (port.bridgeId != bridgeId) {
            throw new BadRequestHttpException(getMessage(MAC_PORT_ON_BRIDGE))
        }
        if (port.vlanId != Bridge.UNTAGGED_VLAN_ID &&
            port.vlanId != macPort.vlanId) {
            throw new BadRequestHttpException(
                getMessage(VLAN_ID_MATCHES_PORT_VLAN_ID, port.vlanId.underlying()))
        }

        macPort.setBaseUri(resourceContext.uriInfo.getBaseUri)

        tryLegacyWrite {
            Await.result(macTable(macPort.vlanId)
                             .addPersistent(mac, macPort.portId), requestTimeout)
            Response.created(macPort.getUri).build()
        }
    }

    @DELETE
    @Path("{mac_port}")
    override def delete(@PathParam("mac_port") macPort: String): Response = {
        val (mac, portId) = parseMacPort(macPort)
        val vlanId = vlanOption.getOrElse(Bridge.UNTAGGED_VLAN_ID)

        validateVlanId(vlanId)

        tryLegacyWrite {
            Await.result(macTable(vlanId).removePersistent(mac, portId),
                         requestTimeout)
            Response.noContent().build()
        }
    }

    private def bridgeVlanIds: Set[Short] = {
        Await.result(stateTableStore.tableArguments(classOf[Network], bridgeId,
                                                    MidonetBackend.MacTable),
                     requestTimeout).map(java.lang.Short.valueOf(_).toShort)
    }

    @throws[NotFoundHttpException]
    private def validateVlanId(vlanId: Short): Unit = {
        if (!bridgeVlanIds.contains(vlanId)) {
            throw new NotFoundHttpException(
                getMessage(BRIDGE_HAS_VLAN, vlanId.underlying()))
        }
    }

    private def macTable(vlanId: Short): StateTable[MAC, UUID] = {
        stateTableStore.bridgeMacTable(bridgeId, vlanId)
    }

    private def parseMacPort(macPort: String): (MAC, UUID) = {
        val tokens = macPort.split("_")
        val mac =
            try MAC.fromString(tokens(0).replace('-', ':'))
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(MAC_URI_FORMAT))
            }
        val id = UUID.fromString(tokens(1))
        (mac, id)
    }
}
