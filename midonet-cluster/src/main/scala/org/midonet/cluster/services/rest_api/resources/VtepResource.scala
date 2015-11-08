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

import scala.collection.JavaConversions._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.State.VtepConnectionState._
import org.midonet.cluster.models.State.{VtepConfiguration, VtepConnectionState}
import org.midonet.cluster.rest_api._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.TunnelZone.TunnelZoneType
import org.midonet.cluster.rest_api.models.Vtep.ConnectionState._
import org.midonet.cluster.rest_api.models.{TunnelZone, Vtep}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.southbound.vtep.{OvsdbVtepConnectionProvider, OvsdbVtepDataClient}
import org.midonet.util.reactivex._

@ApiResource(version = 1)
@Path("vteps")
@RequestScoped
@AllowGet(Array(APPLICATION_VTEP_JSON_V2,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_VTEP_COLLECTION_JSON_V2,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VTEP_JSON_V2,
                   APPLICATION_JSON))
@AllowDelete
class VtepResource @Inject()(resContext: ResourceContext,
                             cnxnProvider: OvsdbVtepConnectionProvider)
    extends MidonetResource[Vtep](resContext) {

    @Path("{id}/bindings")
    def bindings(@PathParam("id") vtepId: UUID): VtepBindingResource = {
        new VtepBindingResource(vtepId, resContext, cnxnProvider)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") vtepId: UUID): VtepPortResource = {
        new VtepPortResource(vtepId, resContext, cnxnProvider)
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
            val msg = getMessage(TUNNEL_ZONE_ID_IS_INVALID)
            throw new BadRequestHttpException(msg)
        }

        getResource(classOf[TunnelZone], vtep.tunnelZoneId) recover {
            // Validate the tunnel zone exists.
            case e: NotFoundException =>
                val msg = getMessage(TUNNEL_ZONE_ID_IS_INVALID)
                throw new BadRequestHttpException(msg)
        } flatMap { tunnelZone =>
            // Validate this is a VTEP tunnel-zone
            if (tunnelZone.`type` != TunnelZoneType.vtep) {
                val msg = getMessage(TUNNEL_ZONE_NOT_VTEP)
                throw new BadRequestHttpException(msg)
            }

            listResources(classOf[Vtep])
        } map { vteps =>
            // Validate there is no conflict with existing VTEPs.
            for (v <- vteps if v.managementIp == vtep.managementIp) {
                val msg = getMessage(VTEP_EXISTS, vtep.managementIp)
                throw new ConflictHttpException(msg)
            }
        } getOrThrow

        // Verify there is no conflict between hosts and the VTEP IPs.
        val mgmtIp = IPv4Addr.fromString(vtep.managementIp)
        val cnxn = cnxnProvider.get(mgmtIp, vtep.managementPort)
        val client = OvsdbVtepDataClient(cnxn)

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

            // Typically we'd go to backend.store directly, however we care
            // about host ips and addresses so we delegate on HostResource to
            // do the composition for us.
            val hostResource = new HostResource(resContext)
            val hostIps =
                hostResource.list(APPLICATION_HOST_COLLECTION_JSON_V3)
                            .flatMap { _.addresses.map(IPAddr.fromString) }
                            .toSet

            val commonIps = vtepIps.find(hostIps.contains)
            if (commonIps.nonEmpty) {
                val msg = getMessage(VTEP_HOST_IP_CONFLICT, commonIps.head)
                throw new ConflictHttpException(msg)
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
                val msg = getMessage(VTEP_HAS_BINDINGS, vtep.managementIp)
                throw new BadRequestHttpException(msg)
            }
        } getOrThrow

        NoOps
    }

    private def initVtep(vtep: Vtep): Future[Vtep] = {
        getConfiguration(vtep.id) flatMap { config =>
            vtep.name = config.getName
            vtep.description = config.getDescription
            vtep.tunnelIpAddrs = config.getTunnelAddressesList.map(_.getAddress)
            getConnectionState(vtep.id)
        } map { connectionState =>
            vtep.connectionState = connectionState match {
                case VTEP_DISCONNECTED => disconnected
                case VTEP_CONNECTED => connected
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
