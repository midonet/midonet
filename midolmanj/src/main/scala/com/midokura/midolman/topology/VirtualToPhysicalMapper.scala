/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import akka.actor.{ActorRef, Actor}
import java.util.UUID
import akka.event.Logging
import com.google.inject.Inject
import com.midokura.midonet.cluster.client.LocalStateBuilder
import com.midokura.midonet.cluster.Client
import com.midokura.midolman.guice.ComponentInjectorHolder

object VirtualToPhysicalMapper {
    val Name = "VirtualToPhysicalMapper"

    /**
     * Will make the actor fire a `LocalStateReply` message to the sender
     * containing the desired local information for the current
     *
     * @param hostIdentifier is the identifier of the current host.
     */
    case class LocalDatapathRequest(hostIdentifier: UUID)

    /**
     * Carries the local desired state information
     *
     * @param dpName is the name of the local datapath that we want.
     */
    case class LocalDatapathReply(dpName: String)

    case class LocalPortsRequest(hostIdentifier: UUID)

    //    * @param ports is a map from UUID to a pair of (netdevName, XX)
    //    , ports: Map[UUID, (String, String)]


    case class LocalPortsReply(ports: Map[UUID, String])

}

/**
 * The Virtual-Physical Mapping is a component that interacts with Midonet
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

    @Inject
    val midoStore: Client = null

    //
    private var localPortsActors = Map[UUID, ActorRef]()
    private var actorWants = Map[ActorRef, ExpectingState]()
    private var localHostData = Map[UUID, (String, Map[UUID, String])]()

    override def preStart() {
        super.preStart()
        ComponentInjectorHolder.inject(this)
    }

    protected def receive = {
        case LocalDatapathRequest(host) =>
            localPortsActors += (host -> sender)
            actorWants += (sender -> ExpectingDatapath())
            midoStore.getLocalStateFor(host, new MyLocalStateBuilder(self, host))

        case LocalPortsRequest(host) =>
            actorWants += (sender -> ExpectingPorts())
            fireStateUpdates(host)

        case _LocalDataUpdatedForHost(host, datapath, ports) =>
            localHostData += (host -> (datapath -> ports))
            fireStateUpdates(host)

        case value =>
            log.error("Unknown message: " + value)
    }

    private def fireStateUpdates(host: UUID) {
        val callingActor = localPortsActors(host)
        if (callingActor != null) {
            actorWants(callingActor) match {
                case ExpectingDatapath() =>
                    callingActor ! LocalDatapathReply(localHostData(host)._1)
                case ExpectingPorts() =>
                    log.info("Telling: " + callingActor + " to: " + LocalPortsReply(localHostData(host)._2))
                    callingActor ! LocalPortsReply(localHostData(host)._2)
            }
        }
    }

    class MyLocalStateBuilder(actor: ActorRef, host: UUID) extends LocalStateBuilder {
        var ports: Map[UUID, String] = Map()
        var datapathName: String = null

        def setDatapathName(datapathName: String): LocalStateBuilder = {
            this.datapathName = datapathName
            this
        }

        def addLocalPortInterface(portId: UUID, interfaceName: String): LocalStateBuilder = {
            ports += (portId -> interfaceName)
            this
        }

        def removeLocalPortInterface(portId: UUID, interfaceName: String): LocalStateBuilder = {
            ports -= portId
            this
        }

        def build() {
            actor ! _LocalDataUpdatedForHost(host, datapathName, ports)
        }
    }

    case class _LocalDataUpdatedForHost(host: UUID, dpName: String, ports: Map[UUID, String])

    private sealed trait ExpectingState

    private case class ExpectingDatapath() extends ExpectingState

    private case class ExpectingPorts() extends ExpectingState

}

