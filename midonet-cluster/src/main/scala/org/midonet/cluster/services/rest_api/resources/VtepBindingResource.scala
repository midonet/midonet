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
import java.util.{List => JList, UUID}
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.reflect.ClassTag

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

    private val seqDispenser = resContext.seqDispenser

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
    private def loadOrBadRequest[T](id: UUID)(implicit ct: ClassTag[T]): T = {
        try {
            store.get(ct.runtimeClass, id).getOrThrow.asInstanceOf[T]
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(
                    getMessage(RESOURCE_NOT_FOUND,
                               ct.runtimeClass.getSimpleName, id))
        }
    }

    @POST
    @Consumes(Array(APPLICATION_VTEP_BINDING_JSON_V2,
                    APPLICATION_JSON))
    override def create(binding: VtepBinding,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        binding.vtepId = vtepId

        throwIfViolationsOn(binding)

        val vtep = loadOrBadRequest[Topology.Vtep](vtepId)
        val network = loadOrBadRequest[Topology.Network](binding.networkId)
        val mgmtIp = toIPv4Addr(vtep.getManagementIp)
        val mgmtPort  = vtep.getManagementPort
        val cnxn = cnxnProvider.get(mgmtIp, mgmtPort)
        val client = OvsdbVtepDataClient(cnxn)

        val result = client.connect() flatMap { _ =>
            client.physicalSwitch
        } flatMap {
            case Some(physicalSwitch) =>
                Future.sequence(physicalSwitch.ports map client.physicalPort)
            case None =>
                throw new ServiceUnavailableHttpException(
                    s"Cannot add binding to VTEP $mgmtIp:" +
                    s"${vtep.getManagementPort} because the physical " +
                    "switch is not configured")
        } map { ports =>

            if (!ports.flatten.exists(_.name == binding.portName)) {
                val msg = getMessage(VTEP_PORT_NOT_FOUND, mgmtIp,
                                     Int.box(mgmtPort), binding.portName)
                throw new NotFoundHttpException(msg)
            }

            throwIfConflictingBinding(vtep, binding)

            val protoBdg = binding.toProto(classOf[Topology.Vtep.Binding])
                                  .toBuilder
                                  .setVlanId(binding.vlanId)
                                  .setPortName(binding.portName)
                                  .setNetworkId(uuidToProto(binding.networkId))
                                  .build()

            val newVtep = vtep.toBuilder.addBindings(protoBdg).build()

            // The vxLan port id is deterministically generated from the
            // vxlan and network ids.
            findVxPortForVtep(network, vtepId) match {
                case Some(p) =>
                    store.update(newVtep)
                case None =>
                    val vni = if (!network.hasVni) {
                        seqDispenser.next(VxgwVni).getOrThrow
                    } else {
                        network.getVni
                    }

                    val p = makeAVxlanPort(vtep, network)
                    val n = network.toBuilder
                                   .setVni(vni)
                                   .addVxlanPortIds(p.getId)
                                   .build()
                    store.multi (
                        Seq(UpdateOp(newVtep), UpdateOp(n), CreateOp(p))
                    )
            }

            binding.setBaseUri(resContext.uriInfo.getBaseUri)
            OkCreated(binding.getUri)
        } recover {
            case t: Throwable =>
                client.close()
                throw t
        }

        val res = result.getOrThrow
        client.close()
        res
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

    /** Check that the port-vlan pair isn't already used in a binding
      * to this VTEP. It will throw otherwise.
      */
    private def throwIfConflictingBinding(vtep: Topology.Vtep, bdg: VtepBinding)
    : Unit = {
        val conflict = vtep.getBindingsList.find { proto =>
            proto.getVlanId.toShort == bdg.vlanId &&
            proto.getPortName == bdg.portName
        }

        if (conflict.nonEmpty) {
            throw new ConflictHttpException(
                getMessage(VTEP_PORT_VLAN_PAIR_ALREADY_USED,
                           toIPv4Addr(vtep.getManagementIp),
                           Int.box(vtep.getManagementPort), bdg.portName,
                           Short.box(bdg.vlanId),
                           fromProto(conflict.get.getNetworkId)))
        }
    }

    /**
     * Find the VxLAN port for the given VTEP in the given network.
     */
    private def findVxPortForVtep(n: Topology.Network, vtepId: UUID)
    : Option[Topology.Port] = {
        val protoVtepId = uuidToProto(vtepId)
        val ids = n.getVxlanPortIdsList
        val ports = store.getAll(classOf[Topology.Port], ids).getOrThrow
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
                    val newVxPorts = nw.getVxlanPortIdsList - p.getId
                    val newNw = nw.toBuilder
                                  .clearVxlanPortIds()
                                  .addAllVxlanPortIds(newVxPorts)
                                  .build()
                    ops.add(UpdateOp(newNw))
                    ops.add(DeleteOp(classOf[Topology.Port], p.getId))
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
