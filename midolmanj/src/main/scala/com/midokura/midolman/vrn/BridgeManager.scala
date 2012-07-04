/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.Actor
import java.util.UUID
import com.midokura.midolman.state.BridgeZkManager

class BridgeManager(val id: UUID, val mgr: BridgeZkManager) extends Actor {
    def receive = {
        case chain: Chain => println("Got chain update")
    }
}
