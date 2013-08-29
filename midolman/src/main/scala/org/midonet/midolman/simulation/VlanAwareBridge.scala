/*
* Copyright 2013 Midokura Europe SARL
*/

package org.midonet.midolman.simulation

import java.util.UUID
import java.lang.{Short => JShort}
import akka.dispatch.{ExecutionContext, Promise, Future}
import org.midonet.midolman.simulation.Coordinator._
import org.midonet.cluster.client.VlanPortMap
import org.midonet.midolman.topology.FlowTagger
import akka.actor.ActorSystem
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.simulation.Coordinator.DropAction
import org.midonet.midolman.simulation.Coordinator.ToPortAction
import collection.immutable
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
                     (implicit val actorSystem: ActorSystem) extends Device {

    var log = LoggerFactory
        .getSimulationAwareLog(this.getClass)(actorSystem.eventStream)

    override def process(pktCtx: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem): Future[Action] = {

        implicit val packetContext: PacketContext = pktCtx

        log.debug("Vlan bridge, process frame {} ", pktCtx.getFrame)
        log.debug("Current vlan-id port mapping: {}", vlanPortMap)

        pktCtx.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))

        if (trunkPorts.contains(pktCtx.getInPortId)) processFromTrunk
        else processFromVirtualNw
    }

    /**
     * Deals with frames coming from the virtual network, that is, the
     * VlanBridgeInteriorPorts which are mapped to a given VLAN id.
     *
     * The process will just push the associated VLAN id and output from the
     * corresponding trunk.
     */
    def processFromVirtualNw(implicit pktCtx: PacketContext): Future[Action] = {
        val vlanId: JShort = vlanPortMap.getVlan(pktCtx.getInPortId)
        vlanId match {
            case _: JShort =>
                log.debug("Frame from logical port, add vlan id to match {} ",
                          vlanId)
                pktCtx.wcmatch.addVlanId(vlanId)
                Promise.successful(ToPortSetAction(id))
            case _ =>
                log.debug("Frame from port {} without vlan id, DROP",
                          pktCtx.getInPortId)
                Promise.successful(DropAction())
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
    def processFromTrunk(implicit pktCtx: PacketContext): Future[Action] = {

        if (MAC.fromString("01:80:c2:00:00:00").equals(
                pktCtx.getFrame.getDestinationMACAddress)) {
            log.debug("BPDU, send to all trunks")
            Promise.successful(ToPortSetAction(id))
        } else {
            val vlanId = pktCtx.getFrame.getVlanIDs.get(0)
            val outPortId: UUID = vlanPortMap.getPort(vlanId)
            pktCtx.setOutputPort(null)
            outPortId match {
                case p: UUID =>
                    log.debug("Vlan Id {} maps to port {}, remove and send",
                              vlanId, outPortId)
                    pktCtx.wcmatch.removeVlanId(vlanId)
                    Promise.successful(ToPortAction(outPortId))
                case _ =>
                    log.debug("Frame with unknown Vlan Id, discard")
                    Promise.successful(DropAction())
            }
        }
    }

}