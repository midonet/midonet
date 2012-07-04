/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.Actor
import java.util.UUID
import com.midokura.midolman.state.ChainZkManager

class ChainManager(val id: UUID, val mgr: ChainZkManager) extends Actor {
    def receive = {
        case chain: Chain => println("Got chain update")
    }
}
