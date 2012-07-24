/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import com.midokura.util.netlink.dp.{Port, Datapath, Packet}
import vrn.VirtualToPhysicalMapper
import com.midokura.util.netlink.protos.OvsDatapathConnection
import com.midokura.util.netlink.Callback

/**
 * Holder object that keeps the external message definitions
 */
object DatapathController {
    case class Initialize()
}

/**
 * // TODO: mtoader ! Please explain yourself.
 */
class DatapathController(localHostIdentifier: String,
                           datapathConnection: OvsDatapathConnection) extends Actor {
    import DatapathController._

    // TODO: mtoader retrieve the actors properly
    val virtualToPhysicalMapper: ActorRef = null
    val virtualTopology: ActorRef = null

    def receive = {
        /**
         * External message reaction
         */
        case Initialize =>
            doLocalDatapathInitialization()

        /**
         * Reply messages reaction
         */
        case state: VirtualToPhysicalMapper.LocalState =>
            doLocalStateUpdate(state)

        /**
         * internally posted replies reactions
         */
        case onPacketIn(packet) =>
            doPacketIn(packet)

        case value: onNetlinkDatapathPorts =>
            doDatapathPortsUpdate(value.datapath, value.ports)
    }

    private def doPacketIn(packet: Packet) {

    }

    private def doLocalDatapathInitialization() {
        virtualToPhysicalMapper !
            VirtualToPhysicalMapper.LocalStateRequest(localHostIdentifier)
    }

    private def doLocalStateUpdate(localState: VirtualToPhysicalMapper.LocalState) {
        datapathConnection.datapathsGet(localState.datapathName,
            new Callback[Datapath] {
                override def onSuccess(datapath: Datapath) {
                    datapathConnection.portsEnumerate(datapath, new Callback[java.util.Set[Port[_, _]]]{
                        override def onSuccess(ports: java.util.Set[Port[_, _]]) {
                            self ! onNetlinkDatapathPorts(
                                datapath = datapath,
                                ports = ports)
                        }
                    })
                }
            })
    }

    def doDatapathPortsUpdate(datapath: Datapath, set: java.util.Set[Port[_, _]]) {

    }

    /**
     * Called when the netlink library receives a packet in
     *
     * @param packet the received packet
     */
    case class onPacketIn(packet: Packet)

    /**
     * Called from the datapath return call.
     *
     * @param datapath the datapath data
     */
    case class onNetlinkDatapath(datapath: Datapath)

    /**
     * Called from the callback listing the datapath ports.
     *
     * @param datapath the datapath data
     * @param ports the set of ports
     */
    case class onNetlinkDatapathPorts(datapath: Datapath, ports: java.util.Set[Port[_, _]])

}
