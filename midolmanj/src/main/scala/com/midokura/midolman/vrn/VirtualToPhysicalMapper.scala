/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.vrn

import akka.actor.{ActorRef, Actor}
import java.util.UUID
import akka.util.duration._
import akka.event.Logging
import collection.mutable
import com.midokura.midolman.DatapathController.{PortInternalOpReply, CreatePortInternal}
import com.midokura.sdn.dp.Ports

object VirtualToPhysicalMapper {

    val Name = "VirtualToPhysicalMapper"

    /**
     * Will make the actor fire a `LocalStateReply` message to the sender
     * containing the desired local information for the current
     *
     * @param hostIdentifier is the identifier of the current host.
     */
    case class LocalDatapathRequest(hostIdentifier: String)

    /**
     * Carries the local desired state information
     *
     * @param dpName is the name of the local datapath that we want.
     */
    case class LocalDatapathReply(dpName: String)

    case class LocalPortsRequest(hostIdentifier: String)

    //    * @param ports is a map from UUID to a pair of (netdevName, XX)
    //    , ports: Map[UUID, (String, String)]


    case class LocalPortsReply(ports: Map[UUID, String])

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

    val log = Logging(context.system, this)
    val localPortsActors: mutable.Map[String, ActorRef] = mutable.Map[String, ActorRef]()

    protected def receive = {
        case LocalDatapathRequest(host) =>
            // TODO: Implement this properly
            log.info("Got local state request for host: " + host)
            sender ! LocalDatapathReply("new_test")

        case LocalPortsRequest(host) =>
            localPortsActors.put(host, sender)
            sender ! LocalPortsReply(
                ports = Map(
                    UUID.randomUUID() -> "xx",
                    UUID.randomUUID() -> "xy"
                ))

            log.info("Will send event in 2 seconds to: {}", localPortsActors.get(host).get)
            context.system.scheduler.scheduleOnce(2 seconds,
                localPortsActors.get(host).get,
                LocalPortsReply(
                    ports = Map(UUID.randomUUID() -> "xz",
                        UUID.randomUUID() -> "xy")))

        case value =>
            log.error("Unknown message: " + value)
    }
}

