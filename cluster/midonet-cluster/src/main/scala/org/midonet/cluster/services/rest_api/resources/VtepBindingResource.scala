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

import java.net.URLEncoder
import java.util
import java.util.{List => JList, UUID}
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.models.{Vtep, VtepBinding}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException, ServiceUnavailableHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkCreated, OkNoContentResponse, ResourceContext}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.southbound.vtep.OvsdbVtepDataClient

@RequestScoped
class VtepBindingResource @Inject()(vtepId: UUID, resContext: ResourceContext)
    extends MidonetResource[VtepBinding](resContext) {

    private val store = resContext.backend.store

    @GET
    @Path("{portName}/{vlanId}")
    @Produces(Array(APPLICATION_VTEP_BINDING_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("portName") portName: String,
            @PathParam("vlanId") vlanId: Short): VtepBinding = {

        getResource(classOf[Vtep], vtepId) map { vtep =>
            val binding = vtep.bindings.asScala
                .find(binding =>
                          binding.portName == portName &&
                          binding.vlanId == vlanId)
                .getOrElse(throw new NotFoundHttpException(
                    getMessage(VTEP_BINDING_NOT_FOUND)))
            binding.setBaseUri(resContext.uriInfo.getBaseUri)
            binding.vtepId = vtepId
            binding
        } getOrThrow
    }

    @GET
    @Produces(Array(APPLICATION_VTEP_BINDING_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[VtepBinding] = {
        getResource(classOf[Vtep], vtepId) map {
            _.bindings.asScala
                      .map(binding => {
                          binding.setBaseUri(resContext.uriInfo.getBaseUri)
                          binding.vtepId = vtepId
                          binding
                      })
                      .asJava
        } getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_VTEP_BINDING_JSON,
                    APPLICATION_JSON))
    override def create(binding: VtepBinding,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        throwIfViolationsOn(binding)

        // Validate the port name.
        if (URLEncoder.encode(binding.portName, "UTF-8") != binding.portName) {
            throw new BadRequestHttpException(
                s"Port name ${binding.portName} contains invalid URI characters")
        }

        val network = try {
            store.get(classOf[Topology.Network], binding.networkId).getOrThrow
        } catch {
            case t: NotFoundException =>
                throw new NotFoundHttpException(
                    getMessage(RESOURCE_NOT_FOUND, "Bridge", binding.networkId))
        }

        // Validate the physical port exists.
        val vtep = store.get(classOf[Topology.Vtep], vtepId).getOrThrow

        val mgmtIp = IPAddressUtil.toIPv4Addr(vtep.getManagementIp)
        val mgmtPort  = vtep.getManagementPort
        val client = OvsdbVtepDataClient(mgmtIp, mgmtPort)

        try {
            val result = client.connect() flatMap { _ =>
                client.physicalSwitch
            } flatMap {
                case Some(physicalSwitch) =>
                    val futures = for (portId <- physicalSwitch.ports) yield
                    client.physicalPort(portId)
                    Future.sequence(futures)
                case None =>
                    throw new ServiceUnavailableHttpException(
                        s"Cannot add binding to VTEP $mgmtIp:" +
                        s"${vtep.getManagementPort} because the physical " +
                        "switch is not configured")
            } map { ports =>

                if (!ports.flatten.exists(_.name == binding.portName)) {
                    throw new ServiceUnavailableHttpException(
                        s"Cannot add binding to VTEP $mgmtIp:" +
                        s"$mgmtPort because the physical " +
                        s"port ${binding.portName} does not exist")
                }

                ensureBindingDoesntExist(vtep, binding)

                binding.vtepId = fromProto(vtep.getId)
                val protoBdg = binding.toProto(classOf[Topology.Vtep.Binding])
                                      .toBuilder
                                      .setVlanId(binding.vlanId)
                                      .setPortName(binding.portName)
                                      .setNetworkId(toProto(binding.networkId))
                                      .build()

                val newVtep = vtep.toBuilder.addBindings(protoBdg).build()

                // The vxLan port id is deterministically generated from the
                // vxlan and network ids.
                findVxPortForVtep(network, vtepId) match {
                    case Some(p) =>
                        store.update(newVtep)
                    case None =>
                        val p = makeAVxlanPort(vtep, network)
                        val n = network.toBuilder.addVxlanPortIds(p.getId).build()
                        store.multi(
                            Seq(CreateOp(p), UpdateOp(n), UpdateOp(newVtep))
                        )
                }

                binding.setBaseUri(resContext.uriInfo.getBaseUri)
                OkCreated(binding.getUri)
            }

            result.getOrThrow

        } finally {
            client.close()
        }
    }

    /** Makes a new VxLAN port for the given vtep and network, that resides
      * on the network itself and has a deterministic id based on both VTEP and
      * network ID.
      */
    private def makeAVxlanPort(vtep: Topology.Vtep,
                               network: Topology.Network): Topology.Port = {
        val nwProtoId = network.getId
        val vxPortId = vtep.getId.xorWith(nwProtoId.getMsb,
                                          nwProtoId.getLsb)

        Topology.Port.newBuilder()
                .setId(vxPortId)
                .setVtepId(vtep.getId)
                .setNetworkId(network.getId)
                .build()
    }

    private def ensureBindingDoesntExist(vtep: Topology.Vtep, bdg: VtepBinding)
    : Unit = {
        val binding = vtep.getBindingsList.find { proto =>
            fromProto(proto.getNetworkId) == bdg.networkId &&
            proto.getVlanId.toShort == bdg.vlanId &&
            proto.getPortName == bdg.portName
        }

        if (binding.nonEmpty) {
            throw new BadRequestHttpException(
                getMessage(VTEP_PORT_VLAN_PAIR_ALREADY_USED,
                           IPAddressUtil.toIPv4Addr(vtep.getManagementIp),
                           Int.box(vtep.getManagementPort), bdg.portName,
                           Short.box(bdg.vlanId), bdg.networkId))
        }
    }

    /**
     * Find the VxLAN port for the given VTEP in the given network.
     */
    private def findVxPortForVtep(n: Topology.Network, vtepId: UUID)
    : Option[Topology.Port] = {
        val protoVtepId = toProto(vtepId)
        val ids = n.getVxlanPortIdsList
        val ports = store.getAll(classOf[Topology.Port], ids).getOrThrow
        ports.find { _.getVtepId == protoVtepId }
    }


    @DELETE
    @Path("{portName}/{vlanId}")
    def delete(@PathParam("portName") portName: String,
               @PathParam("vlanId") vlanId: Short): Response = {
        // Validate the physical port exists.
        val vtep = store.get(classOf[Topology.Vtep], vtepId).getOrThrow
        val newBindings = new util.ArrayList[Topology.Vtep.Binding]
        var removedBinding: Topology.Vtep.Binding = null
        vtep.getBindingsList.foreach { b =>
            if (b.getPortName == portName && b.getVlanId == vlanId) {
                removedBinding = b
            } else {
                newBindings.add(b)
            }
        }

        if (removedBinding == null) { // Idempotent
            return OkNoContentResponse
        }

        val newVtep = vtep.toBuilder.clearBindings()
                                    .addAllBindings(newBindings).build()

        val bindingsLeftOnNetwork = newBindings.count {
            _.getNetworkId == removedBinding.getNetworkId
        }

        val ops = new util.ArrayList[PersistenceOp]
        val nwId = fromProto(removedBinding.getNetworkId)
        if (bindingsLeftOnNetwork <= 0) {
            log.debug(s"No bindings left from $vtepId to network $nwId")
            try {
                val nw = store.get(classOf[Topology.Network],
                               removedBinding.getNetworkId).getOrThrow
                findVxPortForVtep(nw, vtepId) map { p =>
                    // Exclude this port from the network's vxlan ports
                    val newVxPorts = nw.getVxlanPortIdsList.filterNot {
                        _ == p.getId
                    }
                    val newNw = nw.toBuilder
                                  .clearVxlanPortIds()
                                  .addAllVxlanPortIds(newVxPorts)
                                  .build()
                    ops.add(DeleteOp(classOf[Topology.Port], p.getId))
                    ops.add(UpdateOp(newNw))
                }
            } catch {
                case t: NotFoundException =>
                    log.info(s"No vxlan port found for network $nwId and VTEP" +
                             s" $vtepId when trying to remove it after all " +
                             "bindings are deleted")
                 // idempotent
            }
        }

        ops.add(UpdateOp(newVtep))
        store.multi(ops)
        OkNoContentResponse
    }

}
