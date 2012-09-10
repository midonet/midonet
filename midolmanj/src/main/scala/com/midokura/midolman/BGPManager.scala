/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import akka.pattern.ask
import com.midokura.midonet.cluster.DataClient
import com.google.inject.Inject
import datapath.FlowActionVrnPortOutput
import java.util.UUID
import com.midokura.util.functors.Callback2
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.{PortRequest, BGPLinkDeleted, BGPListUnsubscribe, BGPListRequest}
import com.midokura.midonet.cluster.client.{ExteriorRouterPort, BGPLink}
import java.io.{IOException, File}
import org.newsclub.net.unix.{AFUNIXSocketAddress, AFUNIXServerSocket}
import com.midokura.quagga.{ZebraServer, BgpConnection, BgpVtyConnection, ZebraServerImpl}
import com.midokura.packets._
import com.midokura.midolman.DatapathController.{PortInternalOpReply, CreatePortInternal}
import akka.util.Timeout
import akka.util.duration._
import util.{Net, Sudo}
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.sdn.dp.flows.{FlowActions, FlowActionUserspace}
import com.midokura.sdn.dp.Ports
import com.midokura.midonet.cluster.data.BGP

object BGPManager extends Referenceable {
    val Name = "BGPManager"
}

class BGPManager extends Actor with ActorLogging {

    @Inject
    var dataClient: DataClient = null
    private final val BGP_PORT_NAME: String = "midobgp%d"
    private var zebra: ZebraServer = null
    private var bgpd: BgpConnection = null

    private var bgpPortIdx = 0
    private var run = false
    private val BGP_TCP_PORT: Short = 179

    case class LocalPortActive(portID: UUID, active: Boolean)
    case class KillBgp(bgpID: UUID)

    val localPortsCB = new Callback2[UUID, java.lang.Boolean]() {
        def call(portID: UUID, active: java.lang.Boolean) {
            self ! LocalPortActive(portID, active)
        }
    }

    override def preStart() {
        super.preStart()
        // The internal BGP daemon is started only when a switch connects
        // to midolmanj. In a two midolman daemons setup the order in which the
        // switch would connect is essentially random.
        // As such the only way to truly decide which of the daemons
        // (since it can be only one given the quagga package limitations)
        // has the BGP functionality enabled is to force it in the configuration
        // file.
        val socketFile = new File("/var/run/quagga/zserv.api")
        val socketDir = socketFile.getParentFile
        if (!socketDir.exists()) {
            socketDir.mkdirs()
            // Set permission to let quagga daemons write.
            socketDir.setWritable(true, false)
        }

        if (socketFile.exists())
            socketFile.delete()

        val server = AFUNIXServerSocket.newInstance()
        val address = new AFUNIXSocketAddress(socketFile)

        dataClient.subscribeToLocalActivePorts(localPortsCB)

        //TODO(abel) must inject Zk dependencies to zebra
        zebra = new ZebraServerImpl(server, address,
                    null, null, null)

        bgpd = new BgpVtyConnection(
            addr = "localhost",
            port = 2605,
            password =  "zebra_password",
            bgpZk = null,
            adRouteZk = null)


    }

    override def receive = {
        case LocalPortActive(portID, active) =>
            // Register/unregister for update on this port's BGP list.
            if(active)
                VirtualTopologyActor.getRef() ! BGPListRequest(portID, update = false)
            else {
                VirtualTopologyActor.getRef() ! BGPListUnsubscribe(portID)
                // TODO(pino): tear down any BGPs for this port.
            }

        case bgpLink: BGPLink =>
            handleBGPLink(bgpLink)

        case BGPLinkDeleted(bgpID) =>
          self ! KillBgp(bgpID)
          FlowController.getRef().tell(FlowController.InvalidateFlowsByTag(bgpID))

        case PortInternalOpReply(internalPort, _, false, null, Some(pair)) =>
            pair match {
                case Pair(bgpPort:ExteriorRouterPort, bgpLink:BGPLink) =>

                    // connect datapath flows
                    setBGPFlows(internalPort.getPortNo.shortValue(), bgpLink, bgpPort)

                    // launch bgpd
                    //val bgpConfig = new BgpConfig(bgpLink.portID,
                    //    bgpLink.localAS, Net.convertIntToInetAddress(bgpLink
            //.peerAddr.getAddress), bgpLink.peerAS)

                    // where to get the local address?
                    //val localAddr: Int = 0
                    //launchBGPd(bgpLink.bgpID, bgpConfig, localAddr)
            }

        case KillBgp(bgpID) =>
            //TODO(abel) so far we kill all bgps, better to selectively kill by ID
            try {
                Sudo.sudoExec("killall bgpd")
                this.run = false
            } catch {
                case e:InterruptedException =>
                    log.warning("exception killing bgpd: ", e)
            }
    }


    /**
     * We can consider that these things have been created beforehand:
     * - ExteriorRouterPort
     * - Datapath port has been created to match the ExteriorRouterPort
     * - OS Interface has been configured
     */
    def handleBGPLink(bgpLink: BGPLink) {
        implicit val timeout = Timeout(5 seconds)

        val future = VirtualTopologyActor.getRef() ? PortRequest(bgpLink.portID, update = false)
        future onSuccess  {
            case bgpPort: ExteriorRouterPort =>

                // Create a new internal port name
                val portName = BGP_PORT_NAME.format(bgpPortIdx)
                bgpPortIdx += 1

                // The length of interface names are limited to 16 bytes.
                if (portName.length() > 16) {
                    throw new RuntimeException(
                        "The name of the bgp service port is too long")
                }
                log.info("Adding internal port {} for BGP link", portName)

                // TODO(abel) check that the actor that sends this message
                // is not the temporary one, but BGPManager actor
                DatapathController.getRef() !
                    CreatePortInternal(
                        Ports.newInternalPort(portName)
                            .setAddress(bgpPort.portMac.getAddress),
                        tag = Some(Pair(bgpPort: ExteriorRouterPort, bgpLink: BGPLink)))

            case _ => // failure
        }
    }

    def setBGPFlows(localPortNum: Short, bgp: BGPLink, bgpPort: ExteriorRouterPort) {

        // Set the BGP ID in a set to use as a tag for the datapath flows
        // For some reason AddWilcardFlow needs a mutable set so this
        // construction is needed although I'm sure you can find a better one.
        val bgpTagSet = Set[AnyRef](bgp.bgpID)

        // TCP4:->179 bgpd->link
        var wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.peerAddr)
            .setTransportDestination(BGP_TCP_PORT)

        var wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionVrnPortOutput(bgp.portID))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // TCP4:179-> bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.peerAddr)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionVrnPortOutput(bgp.portID))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // TCP4:->179 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgp.portID)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.peerAddr)
            .setNetworkDestination(bgpPort.portAddr)
            .setTransportDestination(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // TCP4:179-> link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgp.portID)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.peerAddr)
            .setNetworkDestination(bgpPort.portAddr)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ARP bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionVrnPortOutput(bgp.portID))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ARP link->bgpd, link->midolman
        // TODO(abel) send ARP from link to both ports only if it's an ARP reply
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))
            .addAction(new FlowActionUserspace)

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ICMP4 bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.peerAddr)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionVrnPortOutput(bgp.portID))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ICMP4 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgp.portID)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.peerAddr)
            .setNetworkDestination(bgpPort.portAddr)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))
    }

    def launchBGPd(bgpId: UUID, bgpConfig: BGP, localAddr: Int) {
        if (!this.run) {
            try {
                Sudo.sudoExec("killall bgpd")
            } catch {
                case e:InterruptedException =>
                    log.warning("exception killing bgpd: ", e)
            }

            zebra.start()

            log.debug("start: launching bgpd")
            Runtime.getRuntime.exec("sudo /usr/lib/quagga/bgpd")

            //TODO(abel) make into a future
            Runtime.getRuntime addShutdownHook new Thread {
                new Runnable () {
                    override def run() {
                        log.info("killing bgpd")
                        // Calling killall because bgpdProcess.destroy()
                        // doesn't seem to work.
                        try {
                            Sudo.sudoExec("killall bgpd")
                        } catch {
                            case e: IOException =>
                                log.warning("killall bgpd", e)
                            case e: InterruptedException =>
                                log.warning("killall bgpd", e)
                        }
                    }
                }
            }

            // Need to wait for bgpd to come up before sending command.
            // delay for 1 second
            Thread.sleep(1000)

            try {
                log.debug("start,Runnable.run: setting bgp config")
                //bgpd.create(Net.convertIntToInetAddress(localAddr),
                //    bgpId, bgpConfig)
            } catch {
                case e: Exception => e.printStackTrace()
            }

            this.run = true
        } else {
            bgpd.create(Net.convertIntToInetAddress(localAddr), bgpId,
                bgpConfig)
        }
    }

}
