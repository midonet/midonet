/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import akka.dispatch.{Future, Await}
import akka.pattern.ask
import akka.util.Timeout

import org.midonet.cluster.data._
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor._

trait VirtualTopologyHelper {
    this: MockMidolmanActors =>

    def preloadTopology(entities: Entity.Base[_,_,_]*)
                       (implicit timeout: Timeout)=
        Await.result(Future.sequence(entities map buildRequest map
                                     { ask(VirtualTopologyActor, _) }),
                     timeout.duration)

    @inline
    private[this] def buildRequest(entity: Entity.Base[_,_,_]) = entity match {
        case p: Port[_, _] => PortRequest(p.getId, update = true)
        case b: Bridge => BridgeRequest(b.getId, update = true)
        case r: Router => RouterRequest(r.getId, update = true)
        case c: Chain => ChainRequest(c.getId, update = true)
    }
}
