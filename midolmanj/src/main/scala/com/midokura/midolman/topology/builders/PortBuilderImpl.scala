/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.topology.builders

import com.midokura.midonet.cluster.client.{Port, PortBuilder}
import akka.actor.ActorRef
import com.midokura.midolman.topology.PortManager


class PortBuilderImpl(val portActor: ActorRef) extends PortBuilder{
    private var port: Port[_] = null
    def setPort(p: Port[_]) {
        port = p
    }

    def build() {
        if (port != null){
        portActor ! PortManager.TriggerUpdate(port)
        }
    }
}
