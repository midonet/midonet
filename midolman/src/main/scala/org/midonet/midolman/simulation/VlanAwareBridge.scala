/*
* Copyright 2013 Midokura Europe SARL
*/

package org.midonet.midolman.simulation

import collection.immutable
import java.lang.{Short => JShort}
import java.util.UUID

import akka.actor.ActorSystem
import akka.dispatch.{ExecutionContext, Promise, Future}

import org.midonet.cluster.client.VlanPortMap
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.topology.FlowTagger
import org.midonet.packets.MAC

/**
 * This is a VLAN-aware bridge created to support the L2 gateway feature.
 *
 * VLAN-aware bridges have 2 materialized ports, and a number of logical ports.
 * The logical ports are associated to a given VLAN-ID. The device takes care
 * of distributing L2 traffic to the correct logical port.
 *
 * BPDU frames don't even get here, a flow is installed beforehand to forward it
 * from one to the other materialized port.
 */
class VlanAwareBridge(val id: UUID,
                      val tunnelKey: Long,
                      val vlanPortMap: VlanPortMap,
                      val trunkPorts: immutable.Set[UUID])
                     (implicit val actorSystem: ActorSystem) extends Coordinator.Device {

    import Coordinator._

    var log = LoggerFactory
        .getSimulationAwareLog(this.getClass)(actorSystem.eventStream)

    override def process(pktCtx: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext: PacketContext = pktCtx

        log.debug("Vlan bridge, process frame {} ", pktCtx.getFrame)
        log.debug("Current vlan-id port mapping: {}", vlanPortMap)

        pktCtx.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))

        val res = if (trunkPorts.contains(pktCtx.getInPortId)) processFromTrunk
            else processFromVirtualNw
        Promise.successful(res)
    }

    /**
     * Deals with frames coming from the virtual network, that is, the
     * VlanBridgeInteriorPorts which are mapped to a given VLAN id.
     *
     * The process will just push the associated VLAN id and output from the
     * corresponding trunk.
     */
    private def processFromVirtualNw(implicit pktCtx: PacketContext): Action = {
        val vlanId: JShort = vlanPortMap.getVlan(pktCtx.getInPortId)
        if (vlanId == null) {
            log.debug(
                "Frame from port {} without vlan id, DROP", pktCtx.getInPortId)
            DropAction
        } else {
            log.debug(
                "Frame from logical port, add vlan id to match {} ", vlanId)
            pktCtx.wcmatch.addVlanId(vlanId)
            ToPortSetAction(id)
        }
    }

    /**
     * We have a frame coming from the trunk port.
     *
     * If the frame contains a BPDU, it'll return a ToPortSetAction so that
     * it gets send to all the trunks.
     *
     * If it does not, it'll read the VLAN ID, check if there is a VLAN interior
     * port with it, if so strip the VLAN tag and send the frame over there.
     * Otherwise, drop.
     */
    private def processFromTrunk(implicit pktCtx: PacketContext): Action =
        if (MAC.fromString("01:80:c2:00:00:00").equals(
                pktCtx.getFrame.getDestinationMACAddress)) {
            log.debug("BPDU, send to all trunks")
            ToPortSetAction(id)
        } else {
            pktCtx.setOutputPort(null)
            val vlanId = pktCtx.getFrame.getVlanIDs.get(0)
            val outPortId: UUID = vlanPortMap.getPort(vlanId)
            if (outPortId == null) {
                log.debug("Frame with unknown Vlan Id, discard")
                DropAction
            } else {
                log.debug("Vlan Id {} maps to port {}, remove and send",
                          vlanId, outPortId)
                pktCtx.wcmatch.removeVlanId(vlanId)
                ToPortAction(outPortId)
            }
        }

}
