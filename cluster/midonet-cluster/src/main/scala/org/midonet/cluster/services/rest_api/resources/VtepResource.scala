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

import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.State.{VtepConnectionState, VtepConfiguration}
import org.midonet.cluster.models.State.VtepConnectionState._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.TunnelZone.TunnelZoneType
import org.midonet.cluster.rest_api.models.Vtep.ConnectionState._
import org.midonet.cluster.rest_api.models.{Host, TunnelZone, Vtep}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbVtepDataClient
import org.midonet.util.reactivex._

@ApiResource(version = 1)
@Path("vteps")
@RequestScoped
@AllowGet(Array(APPLICATION_VTEP_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_VTEP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VTEP_JSON,
                   APPLICATION_JSON))
@AllowDelete
class VtepResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Vtep](resContext) {

    @Path("{id}/bindings")
    def bindings(@PathParam("id") vtepId: UUID): VtepBindingResource = {
        new VtepBindingResource(vtepId, resContext)
    }

    protected override def getFilter(vtep: Vtep): Future[Vtep] = {
        initVtep(vtep)
    }

    protected override def listFilter(vteps: Seq[Vtep]): Future[Seq[Vtep]] = {
        Future.sequence(for (vtep <- vteps) yield initVtep(vtep))
    }

    protected override def createFilter(vtep: Vtep): Ops = {

        throwIfViolationsOn(vtep)

        // Validate the tunnel zone.
        if (vtep.tunnelZoneId eq null) {
            throw new BadRequestHttpException(
                getMessage(TUNNEL_ZONE_ID_IS_INVALID))
        }

        getResource(classOf[TunnelZone], vtep.tunnelZoneId) recover {
            // Validate the tunnel zone exists.
            case e: NotFoundException =>
                throw new BadRequestHttpException(
                    getMessage(TUNNEL_ZONE_ID_IS_INVALID))
        } flatMap { tunnelZone =>
            // Validate this is a VTEP tunnel-zone
            if (tunnelZone.`type` != TunnelZoneType.vtep) {
                throw new BadRequestHttpException(
                    getMessage(TUNNEL_ZONE_NOT_VTEP))
            }

            listResources(classOf[Vtep])
        } map { vteps =>
            // Validate there is no conflict with existing VTEPs.
            for (v <- vteps if v.managementIp == vtep.managementIp) {
                throw new ConflictHttpException(
                    getMessage(VTEP_HOST_IP_CONFLICT))
            }
        } getOrThrow

        // Verify there is no conflict between hosts and the VTEP IPs.
        val client = OvsdbVtepDataClient(IPv4Addr(vtep.managementIp),
                                         vtep.managementPort)
        try {
            val vtepIps = client.connect() flatMap { _ =>
                client.physicalSwitch
            } map {
                case Some(physicalSwitch) =>
                    (physicalSwitch.mgmtIpStrings.map(IPv4Addr.fromString) ++
                    physicalSwitch.tunnelIpStrings.map(IPv4Addr.fromString) ++
                    Seq(IPv4Addr(vtep.managementIp))).toSet
                case None =>
                    throw new ServiceUnavailableHttpException(
                        s"Cannot connect to VTEP at ${vtep.managementIp}:" +
                        s"${vtep.managementPort} because the physical switch " +
                        "is not configured")
            } getOrThrow

            val hostIps = listResources(classOf[Host]) map {
                _.flatMap(_.addresses.asScala.map(IPv4Addr.fromString)).toSet
            } getOrThrow

            val commonIps = vtepIps.intersect(hostIps)
            if (commonIps.nonEmpty) {
                throw new ConflictHttpException(getMessage(VTEP_HOST_IP_CONFLICT,
                                                           commonIps.head))
            }
        } finally {
            client.close()
        }

        vtep.create()
        NoOps
    }

    protected override def deleteFilter(id: String): Ops = {
        getResource(classOf[Vtep], id) map { vtep =>
            // Validate the VTEP has no bindings.
            if (vtep.bindings.size() > 0) {
                throw new BadRequestHttpException(getMessage(VTEP_HAS_BINDINGS,
                                                             id))
            }
        } getOrThrow

        NoOps
    }

    private def initVtep(vtep: Vtep): Future[Vtep] = {
        getConfiguration(vtep.id) flatMap { config =>
            vtep.name = config.getName
            vtep.description = config.getDescription
            vtep.tunnelIpAddrs =
                config.getTunnelAddressesList.asScala.map(_.getAddress).asJava
            getConnectionState(vtep.id)
        } map { connectionState =>
            vtep.connectionState = connectionState match {
                case VTEP_DISCONNECTED => disconnected
                case VTEP_DISCONNECTING => disconnecting
                case VTEP_CONNECTED => connected
                case VTEP_CONNECTING => connecting
                case VTEP_READY => ready
                case VTEP_BROKEN => broken
                case VTEP_FAILED => failed
                case VTEP_ERROR => error
            }
            vtep
        }
    }

    private def getConfiguration(vtepId: UUID): Future[VtepConfiguration] = {
        resContext.backend.stateStore.getVtepConfig(vtepId).asFuture
    }

    private def getConnectionState(vtepId: UUID): Future[VtepConnectionState] = {
        resContext.backend.stateStore.getVtepConnectionState(vtepId).asFuture
    }

}
