/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import java.util.UUID

import org.midonet.midolman.{DatapathController, FlowController}
import builders.VlanAwareBridgeBuilderImpl
import org.midonet.cluster.Client
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.VlanAwareBridgeManager.TriggerUpdate
import org.midonet.cluster.client.VlanPortMap
import org.midonet.packets.MAC
import org.midonet.midolman.simulation.VlanAwareBridge
import collection.immutable
import org.midonet.midolman.FlowController.InvalidateFlowsByTag

case class VlanAwareBridgeConfig(tunnelKey: Int = 0)

object VlanAwareBridgeManager {
    val Name = "VlanAwareBridgeManager"

    case class TriggerUpdate(cfg: VlanAwareBridgeConfig,
                             vlanToPort: VlanPortMap,
                             trunks: immutable.Set[UUID])
}

class VlanAwareBridgeManager(id: UUID, val clusterClient: Client,
                             val config: MidolmanConfig) extends DeviceManager(id) {
    implicit val system = context.system

    private var cfg: VlanAwareBridgeConfig = null
    private var changed = false

    private var vlanToPort: VlanPortMap = null
    private var trunks: immutable.Set[UUID] = null

    private case class FlowIncrement(mac: MAC, port: UUID)

    private case class FlowDecrement(mac: MAC, port: UUID)

    def getTunnelKey: Long = {
        cfg match {
            case null => 0
            case c => c.tunnelKey
        }
    }

    override def preStart() {
        clusterClient.getVlanAwareBridge(id,
            new VlanAwareBridgeBuilderImpl(id, FlowController.getRef(), self))
    }

    override def receive = super.receive orElse {

        case TriggerUpdate(newCfg, newVlanToPortMap, newTrunks) =>
            log.debug("Received a VlanAwareBridge update from the data store.")
            if (newCfg != cfg && cfg != null) {
                // the cfg of this bridge changed, invalidate all the flows
                changed = true
            }
            vlanToPort = newVlanToPortMap
            if (newTrunks != null && !newTrunks.equals(trunks)) {
                FlowController.getRef() ! InvalidateFlowsByTag.apply(FlowTagger.invalidateFlowsByDevice(id))
            }
            trunks = newTrunks
            cfg = newCfg
            // Notify that the update finished
            configUpdated()

    }

    // TODO (galo) refactor Device hierarchy so this method is only introduced
    // where needed
    def chainsUpdated() {
        log.debug("VlanAwareBridgeManager.chainsUpdated, nothing to do")
        context.actorFor("..").tell(
                    new VlanAwareBridge(id, getTunnelKey, vlanToPort, trunks))

        // TODO (galo, rossella) these two msgs will race, we may want to
        // change where the invalidation is done
        context.actorFor("..") ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(id))
    }

    def getInFilterID: UUID = null

    def getOutFilterID: UUID = null
}