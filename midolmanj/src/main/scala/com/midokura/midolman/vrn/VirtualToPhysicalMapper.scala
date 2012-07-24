/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.vrn

import akka.actor.Actor

object VirtualToPhysicalMapper {

    /**
     * Will make the actor fire a `LocalStateReply` message to the sender
     * containing the desired local information for the current
     *
     * @param hostIdentifier is the identifier of the current host.
     */
    case class LocalStateRequest(hostIdentifier: String)

    /**
     * Carries the local desired state information
     *
     * @param dpName is the name of the local datapath that we want.
     */
    case class LocalStateReply(dpName: String)

}

/**
 * The Virtual-Physical Mapping is a component that interacts with Midonet's
 * state management cluster and is responsible for those pieces of state that
 * map physical world entities to virtual world entities.
 *
 * In particular, the VPM can be used to:
 * <ul>
 * <li>determine what virtual port UUIDs should be mapped to what interfaces
 * (by interface name) on a given physical host. </li>
 * <li> determine what physical hosts are subscribed to a given PortSet. </li>
 * <li> determine what local virtual ports are part of a PortSet.</li>
 * <li> determine all the virtual ports that are part of a PortSet.</li>
 * <li> determine whether a virtual port is reachable and at what physical host
 * (a virtual port is reachable if the responsible host has mapped the vport ID
 * to its corresponding local interface and the interface is ready to receive).
 * </li>
 * </ul>
 */
class VirtualToPhysicalMapper extends Actor {

    import VirtualToPhysicalMapper._

    protected def receive = {
        case req: LocalStateRequest =>
            // TODO: Implement this properly
            sender ! LocalStateReply
    }
}

