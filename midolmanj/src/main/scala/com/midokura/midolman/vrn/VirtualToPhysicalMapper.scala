/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.vrn

import akka.actor.Actor

/**
 * // TODO: mtoader ! Please explain yourself.
 */

object VirtualToPhysicalMapper {

    case class LocalStateRequest(localHostIdentifier: String)

    case class LocalState(datapathName: String)
}

class VirtualToPhysicalMapper extends Actor {
    import VirtualToPhysicalMapper._

    protected def receive = {
        case request: LocalStateRequest =>
            {}
    }
}

