/*
* Copyright 2012 Midokura Pte. Ltd.
*/

package org.midonet.midolman.topology.builders

import akka.actor.ActorRef
import java.util.UUID
import java.lang.{Short => JShort}

import org.slf4j.LoggerFactory

import org.midonet.midolman.topology.{VlanAwareBridgeManager, VlanAwareBridgeConfig}
import org.midonet.util.functors.Callback3
import org.midonet.cluster.client.{VlanPortMap, VlanAwareBridgeBuilder, SourceNatResource, ForwardingElementBuilder}
import collection.immutable
import scala.collection.JavaConversions._

class VlanAwareBridgeBuilderImpl(val id: UUID, val flowController: ActorRef,
                                 val bridgeMgr: ActorRef)
    extends VlanAwareBridgeBuilder {

    final val log = LoggerFactory.getLogger(classOf[VlanAwareBridgeBuilderImpl])

    private var cfg = new VlanAwareBridgeConfig()
    private var vlanPortMap: VlanPortMap = null
    private var trunks: immutable.Set[UUID] = null

    def setTunnelKey(key: Long) {
        cfg = cfg.copy(tunnelKey = key.toInt)
    }

    def start() = null

    def setVlanPortMap(newMap: VlanPortMap) {
        if (newMap != null) vlanPortMap = newMap
    }

    def setTrunks(newTrunks: java.util.Set[UUID]) {
        if (newTrunks != null) this.trunks = newTrunks.toSet
        newTrunks
    }

    // TODO (galo) marked as useless and to-delete in BridgeBuilderImpl
    def setID(id: UUID) = this

    // TODO (galo) These are forced by parents, could we get rid of them?
    def setInFilter(filterID: UUID): ForwardingElementBuilder = null

    def setOutFilter(filterID: UUID): ForwardingElementBuilder = null

    def setSourceNatResource(resource: SourceNatResource) { }

    def build() {
        log.debug("Building the vlan bridge for {} with vlan-port map {}" +
                  Array[Object](id, vlanPortMap, trunks))
        bridgeMgr ! VlanAwareBridgeManager
                    .TriggerUpdate(cfg, vlanPortMap, trunks)
    }

    // TODO (galo) It'd be probably nice to try and share this code with the
    // version in BridgeBuilderImpl I copied this from.
    private class VlanPortMapNotifyCallback extends Callback3[JShort, UUID, UUID] {
        final val log =
            LoggerFactory.getLogger(classOf[VlanPortMapNotifyCallback])

        def call(vlanId: JShort, oldPort: UUID, newPort: UUID) {
            log.debug("Vlan-Port mapping for vlan {} was updated from {} to {}",
                Array(vlanId, oldPort, newPort))

            // A new port is assigned
            if (newPort != null && oldPort == null){
                // TODO (galo) complete this
                log.warn("TODO: invalidate flows by VLAN ID")
            }
        }
    }

}