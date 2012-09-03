/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.{AcquiredLockOnVPN, RegisterVPNHandler}

object VPNManager extends Referenceable {
    val Name = "VPNManager"
}

class VPNManager extends Actor with ActorLogging {

    override def preStart() {
        super.preStart()
        VirtualTopologyActor.getRef() ! RegisterVPNHandler
    }

    override def receive = {
        case AcquiredLockOnVPN(vpnID, acquired) =>
        // case VPN(...) =>
    }
}
