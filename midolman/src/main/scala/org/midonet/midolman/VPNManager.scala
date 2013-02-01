/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman

import akka.actor.{ActorLogging, Actor}
import logging.ActorLogWithoutPath
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.{AcquiredLockOnVPN, RegisterVPNHandler}

object VPNManager extends Referenceable {
    override val Name = "VPNManager"
}

class VPNManager extends Actor with ActorLogWithoutPath {

    override def preStart() {
        super.preStart()
        VirtualTopologyActor.getRef() ! RegisterVPNHandler
    }

    override def receive = {
        case AcquiredLockOnVPN(vpnID, acquired) =>
        // case VPN(...) =>
    }
}
