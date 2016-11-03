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

import java.lang.{Short => JShort}
import java.util
import java.util.{UUID, List => JList}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rest_api.models.{Vtep, VtepBinding}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, ConflictHttpException, NotFoundHttpException, ServiceUnavailableHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkCreated, OkNoContentResponse, ResourceContext}
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.SequenceDispenser.VxgwVni
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto, toProto => uuidToProto}
import org.midonet.southbound.vtep.{OvsdbVtepConnectionProvider, OvsdbVtepDataClient}

@RequestScoped
class VtepBindingResource @Inject()(vtepId: UUID, resContext: ResourceContext,
                                    cnxnProvider: OvsdbVtepConnectionProvider)
    extends MidonetResource[VtepBinding](resContext) {

    @GET
    @Path("{portName}/{vlanId}")
    @Produces(Array(APPLICATION_VTEP_BINDING_JSON_V2,
                    APPLICATION_JSON))
    def get(@PathParam("portName") portName: String,
            @PathParam("vlanId") vlanId: JShort): VtepBinding = {

        val vtep = getResource(classOf[Vtep], vtepId)
        val binding = vtep.bindings.asScala
            .find(binding =>
                      binding.portName == portName &&
                      binding.vlanId == vlanId)
            .getOrElse(throw new NotFoundHttpException(
                getMessage(VTEP_BINDING_NOT_FOUND, vtep.managementIp,
                           vlanId, portName)))
        binding.setBaseUri(resContext.uriInfo.getBaseUri)
        binding.vtepId = vtepId
        binding
    }

    @GET
    @Produces(Array(APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[VtepBinding] = {
        val vtep = getResource(classOf[Vtep], vtepId)
        vtep.bindings.asScala
            .map(binding => {
                binding.setBaseUri(resContext.uriInfo.getBaseUri)
                binding.vtepId = vtepId
                binding
            })
            .asJava
    }

    /* This class should be generalized into MidonetResource.getOrThrow, so that
     * we can selectively map a ZOOM's not found to a 404 or a 400 based on the
     * use case.  Leaving this for its own patch.
     */
    private def loadOrBadRequest[T](tx: Transaction, clazz: Class[T], id: UUID)
    : T = {
        try tx.get(clazz, id)
        catch {
            case t: NotFoundException =>
                throw new BadRequestHttpException(
                    getMessage(RESOURCE_NOT_FOUND, clazz.getSimpleName, id))
        }
    }

    @POST
    @Consumes(Array(APPLICATION_VTEP_BINDING_JSON_V2,
                    APPLICATION_JSON))
    override def create(binding: VtepBinding,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = tryTx { tx =>
        binding.vtepId = vtepId

        throwIfViolationsOn(binding)

        val vtep = loadOrBadRequest(tx.tx, classOf[Topology.Vtep], vtepId)
        val network = loadOrBadRequest(tx.tx, classOf[Topology.Network],
                                       binding.networkId)
        val mgmtIp = toIPv4Addr(vtep.getManagementIp)
        val mgmtPort  = vtep.getManagementPort

        val client = OvsdbVtepDataClient(cnxnProvider.get(mgmtIp, mgmtPort))
        val ports = try {
            client.connect() flatMap { _ =>
                client.physicalSwitch
            } flatMap {
                case Some(physicalSwitch) =>
                    Future.sequence(physicalSwitch.ports map client.physicalPort)
                case None =>
                    throw new ServiceUnavailableHttpException(
                        s"Cannot add binding to VTEP $mgmtIp:" +
                        s"${vtep.getManagementPort} because the physical " +
                        "switch is not configured")
            } getOrThrow
        } finally {
            client.close()
        }

        if (!ports.flatten.exists(_.name == binding.portName)) {
            val msg = getMessage(VTEP_PORT_NOT_FOUND, mgmtIp,
                                 Int.box(mgmtPort), binding.portName)
            throw new NotFoundHttpException(msg)
        }

        if (throwIfConflictingBinding(vtep, binding)) {
            log.debug(s"Idempotent create of VTEP binding $binding")
        } else {
            val protoBdg = binding.toProto(classOf[Topology.Vtep.Binding])
                                  .toBuilder
                                  .setVlanId(binding.vlanId)
                                  .setPortName(binding.portName)
                                  .setNetworkId(uuidToProto(binding.networkId))
                                  .build()

            val newVtep = vtep.toBuilder.addBindings(protoBdg).build()

            // The vxLan port id is deterministically generated from the
            // vxlan and network ids.
            findVxPortForVtep(tx.tx, network, vtepId) match {
                case Some(p) =>
                    tx.tx.update(newVtep)
                case None =>
                    val vni = if (!network.hasVni) {
                        resContext.sequenceDispenser.next(VxgwVni).getOrThrow
                    } else {
                        network.getVni
                    }

                    val p = makeAVxlanPort(vtep, network)
                    val n = network.toBuilder
                                   .setVni(vni)
                                   .addVxlanPortIds(p.getId)
                                   .build()
                    tx.tx.update(newVtep)
                    tx.tx.update(n)
                    tx.tx.create(p)
            }
        }

        binding.setBaseUri(resContext.uriInfo.getBaseUri)
        OkCreated(binding.getUri)
    }

    /** Makes a new VxLAN port for the given vtep and network, that resides
      * on the network itself and has a deterministic id based on both VTEP and
      * network ID.
      */
    private def makeAVxlanPort(vtep: Topology.Vtep,
                               network: Topology.Network): Topology.Port = {
        val vxPortId = vxlanPortId(network.getId, vtep.getId)
        Topology.Port.newBuilder()
                .setId(vxPortId)
                .setVtepId(vtep.getId)
                .setNetworkId(network.getId)
                .build()
    }

    /** Check that there isn't any other network using the same port-vlan
      * pair in a binding to this VTEP.  It will throw otherwise.
      *
      * @return true when the exact same binding already exists,
      *         allowing idempotent binding creation.
      */
    private def throwIfConflictingBinding(vtep: Topology.Vtep, bdg: VtepBinding)
    : Boolean = {
        val conflict = vtep.getBindingsList.asScala.find { proto =>
            proto.getVlanId.toShort == bdg.vlanId &&
            proto.getPortName == bdg.portName
        }

        if (conflict.nonEmpty &&
            conflict.get.getNetworkId.asJava == bdg.networkId) {
            return true
        } else if (conflict.nonEmpty) {
            throw new ConflictHttpException(
                getMessage(VTEP_PORT_VLAN_PAIR_ALREADY_USED,
                           toIPv4Addr(vtep.getManagementIp),
                           Int.box(vtep.getManagementPort), bdg.portName,
                           Short.box(bdg.vlanId),
                           fromProto(conflict.get.getNetworkId)))
        }
        false
    }

    /**
     * Find the VxLAN port for the given VTEP in the given network.
     */
    private def findVxPortForVtep(tx: Transaction, n: Topology.Network, vtepId: UUID)
    : Option[Topology.Port] = {
        val protoVtepId = uuidToProto(vtepId)
        val ids = n.getVxlanPortIdsList.asScala
        val ports = tx.getAll(classOf[Topology.Port], ids)
        ports.find { _.getVtepId == protoVtepId }
    }

    def vxlanPortId(networkId: UUID, vtepId: UUID): UUID = {
        vxlanPortId(uuidToProto(networkId), uuidToProto(vtepId))
    }

    def vxlanPortId(networkId: Commons.UUID,
                    vtepId: Commons.UUID): Commons.UUID = {
        vtepId.xorWith(networkId.getMsb, networkId.getLsb)
    }

    @DELETE
    @Path("{portName}/{vlanId}")
    def delete(@PathParam("portName") portName: String,
               @PathParam("vlanId") vlanId: Short): Response = tryTx { tx =>
        // Validate the physical port exists.
        val vtep = tx.tx.get(classOf[Topology.Vtep], vtepId)
        val newBindings = new util.ArrayList[Topology.Vtep.Binding]
        var removedBinding: Topology.Vtep.Binding = null
        vtep.getBindingsList.asScala.foreach { b =>
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

        val bindingsLeftOnNetwork = newBindings.asScala.count {
            _.getNetworkId == removedBinding.getNetworkId
        }

        val nwId = fromProto(removedBinding.getNetworkId)
        if (bindingsLeftOnNetwork <= 0) {
            log.debug(s"No bindings left from $vtepId to network $nwId")
            try {
                val nw = tx.tx.get(classOf[Topology.Network],
                                   removedBinding.getNetworkId)
                findVxPortForVtep(tx.tx, nw, vtepId) foreach { p =>
                    // Exclude this port from the network's vxlan ports
                    val newVxPorts = nw.getVxlanPortIdsList.asScala - p.getId
                    val newNw = nw.toBuilder
                                  .clearVxlanPortIds()
                                  .addAllVxlanPortIds(newVxPorts.asJava)
                                  .build()
                    tx.tx.update(newNw)
                    tx.tx.delete(classOf[Topology.Port], p.getId)
                }
            } catch {
                case t: NotFoundException =>
                    log.info(s"No VXLAN port found for network $nwId and VTEP " +
                             s"$vtepId when trying to remove it after all " +
                             "bindings are deleted")
                 // idempotent
            }
        }

        tx.tx.update(newVtep)
        OkNoContentResponse
    }

}
