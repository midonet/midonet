/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import topology.VirtualToPhysicalMapper.LocalPortActive

class RemoteServer  extends Actor with ActorLogging {
    import context._

    def receive = {
        case "LocalPortsStart" =>
            system.eventStream.subscribe(sender, classOf[LocalPortActive])
        case "LocalPortsStop" =>
            system.eventStream.unsubscribe(sender, classOf[LocalPortActive])
    }
}
