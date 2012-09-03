/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import com.midokura.midonet.cluster.DataClient
import com.google.inject.Inject
import java.util.UUID
import com.midokura.util.functors.Callback2
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.{BGPLinkDeleted, BGPListUnsubscribe, BGPListRequest}
import com.midokura.midonet.cluster.client.BGPLink

object BGPManager extends Referenceable {
    val Name = "BGPManager"
    case class LocalPortActive(portID: UUID, active: Boolean)
}

class BGPManager extends Actor with ActorLogging {
    import BGPManager._

    @Inject
    var dataClient: DataClient = null;
    val localPortsCB = new Callback2[UUID, java.lang.Boolean]() {
        def call(portID: UUID, active: java.lang.Boolean) {
            self ! LocalPortActive(portID, active)
        }
    }

    override def preStart() {
        super.preStart()
        dataClient.subscribeToLocalActivePorts(localPortsCB)
    }

    override def receive = {
        case LocalPortActive(portID, active) =>
            // Register/unregister for update on this port's BGP list.
            if(active)
                VirtualTopologyActor.getRef() ! BGPListRequest(portID, true)
            else {
                VirtualTopologyActor.getRef() ! BGPListUnsubscribe(portID)
                // TODO(pino): tear down any BGPs for this port.
            }
        case bgp: BGPLink =>
            handleBGP(bgp)

        case BGPLinkDeleted(bgpID) =>

    }

    def handleBGP(bgp: BGPLink) {

    }
}
