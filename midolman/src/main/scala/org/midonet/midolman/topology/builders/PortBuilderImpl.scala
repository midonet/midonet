/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.topology.builders

import org.midonet.cluster.client.{Port, PortBuilder}
import akka.actor.ActorRef
import org.midonet.midolman.topology.PortManager


class PortBuilderImpl(val portActor: ActorRef) extends PortBuilder {

    private var port: Port = null

    def setPort(p: Port) {
        port = p
    }

    def build() {
        if (port != null) {
            portActor ! PortManager.TriggerUpdate(port)
        }
    }
}
