/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import com.midokura.sdn.dp.{Port, Datapath, Packet}
import vrn.VirtualToPhysicalMapper
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.netlink.Callback

/**
 * Holder object that keeps the external message definitions
 */
object DatapathController {

    /**
     * This will make the Datapath Controller to start the local state
     * initialization process.
     */
    case class Initialize()
}

/**
 * The DP (Datapath) Controller is responsible for managing MidoNet's local
 * kernel datapath. It queries the Virt-Phys mapping to discover (and receive
 * updates about) what virtual ports are mapped to this host's interfaces.
 * It uses the Netlink API to query the local datapaths, create the datapath
 * if it does not exist, create datapath ports for the appropriate host
 * interfaces and learn their IDs (usually a Short), locally track the mapping
 * of datapath port ID to MidoNet virtual port ID. When a locally managed vport
 * has been successfully mapped to a local network interface, the DP Controller
 * notifies the Virtual-Physical Mapping that the vport is ready to receive flows.
 * This allows other Midolman daemons (at other physical hosts) to correctly
 * forward flows that should be emitted from the vport in question.
 * The DP Controller knows when the Datapath is ready to be used and notifies
 * the Flow Controller so that the latter may register for Netlink PacketIn
 * notifications. For any PacketIn that the FlowController cannot handle with
 * the already-installed wildcarded flows, DP Controller receives a PacketIn
 * from the FlowController, translates the arriving datapath port ID to a virtual
 * port UUID and passes the PacketIn to the Simulation Controller. Upon receiving
 * a simulation result from the Simulation Controller, the DP is responsible
 * for creating the corresponding wildcard flow. If the flow is being emitted
 * from a single remote virtual port, this involves querying the Virtual-Physical
 * Mapping for the location of the host responsible for that virtual port, and
 * then building an appropriate tunnel port or using the existing one. If the
 * flow is being emitted from a single local virtual port, the DP Controller
 * recognizes this and uses the corresponding datapath port. Finally, if the
 * flow is being emitted from a PortSet, the DP Controller queries the
 * Virtual-Physical Mapping for the set of hosts subscribed to the PortSet;
 * it must then map each of those hosts to a tunnel and build a wildcard flow
 * description that outputs the flow to all of those tunnels and any local
 * datapath port that corresponds to a virtual port belonging to that PortSet.
 * Finally, the wildcard flow, free of any MidoNet ID references, is pushed to
 * the FlowController.
 *
 * The DP Controller is responsible for managing overlay tunnels (see the
 * previous paragraph).
 *
 * The DP Controller notifies the Flow Validation Engine of any installed
 * wildcard flow so that the FVE may do appropriate indexing of flows (e.g. by
 * the ID of any virtual device that was traversed by the flow). The DP Controller
 * may receive requests from the FVE to invalidate specific wildcard flows; these
 * are passed on to the FlowController.
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
        case state: VirtualToPhysicalMapper.LocalStateReply =>
            doLocalStateUpdate(state)

        /**
         * internally posted replies reactions
         */
        case value: _PacketIn =>
            doPacketIn(value.packet)

        case value: _NetlinkDatapathPorts =>
            doDatapathPortsUpdate(value.dp, value.ports)
    }

    private def doPacketIn(packet: Packet) {

    }

    private def doLocalDatapathInitialization() {
        virtualToPhysicalMapper !
            VirtualToPhysicalMapper.LocalStateRequest(localHostIdentifier)
    }

    private def doLocalStateUpdate(state: VirtualToPhysicalMapper.LocalStateReply) {

        datapathConnection.datapathsGet(state.dpName,
            call {
                dp: Datapath =>
                    datapathConnection.portsEnumerate(dp,
                        call {
                            ports =>
                                self ! _NetlinkDatapathPorts(dp = dp, ports = ports)
                        })
            })
    }

    def call[T](code: (T) => Unit): Callback[T] = {
        new Callback[T] {
            override def onSuccess(data: T) {
                code(data)
            }
        }
    }

    def doDatapathPortsUpdate(datapath: Datapath, set: java.util.Set[Port[_, _]]) {

    }

    /**
     * Called when the netlink library receives a packet in
     *
     * @param packet the received packet
     */
    private case class _PacketIn(packet: Packet)

    /**
     * Called from the callback listing the datapath ports.
     *
     * @param dp the datapath data
     * @param ports the set of ports
     */
    private case class _NetlinkDatapathPorts(dp: Datapath, ports: java.util.Set[Port[_, _]])

}
