/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.Actor
import java.util.UUID

class BridgeManager(val id: UUID) extends Actor {
    def receive = {
        case chain: Chain => println("Got chain update")
    }
}
