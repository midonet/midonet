/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import akka.pattern.ask
import com.midokura.midonet.cluster.DataClient
import com.google.inject.Inject
import java.util.UUID
import com.midokura.util.functors.Callback2
import openflow.{ControllerStub, MidoMatch}
import state.zkManagers.BgpZkManager
import state.zkManagers.BgpZkManager.BgpConfig
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.BGPLinkDeleted
import topology.VirtualTopologyActor.BGPListRequest
import topology.VirtualTopologyActor.BGPListUnsubscribe
import topology.VirtualTopologyActor.PortRequest
import topology.VirtualTopologyActor.{PortRequest, BGPLinkDeleted, BGPListUnsubscribe, BGPListRequest}
import com.midokura.midonet.cluster.client.{ExteriorRouterPort, Port, BGPLink}
import java.io.{IOException, File}
import org.newsclub.net.unix.{AFUNIXSocketAddress, AFUNIXServerSocket}
import com.midokura.quagga.{ZebraServer, BgpConnection, BgpVtyConnection, ZebraServerImpl}
import com.midokura.packets._
import com.midokura.midolman.DatapathController.{BindToInternalPort, CreatePortInternal}
import com.midokura.sdn.dp.ports.InternalPort
import akka.util.Timeout
import akka.util.duration._
import com.midokura.midolman.DatapathController.CreatePortInternal
import util.{Net, Sudo}

object BGPManager extends Referenceable {
    val Name = "BGPManager"
    case class LocalPortActive(portID: UUID, active: Boolean)
}

class BGPManager extends Actor with ActorLogging {
    import BGPManager._

    @Inject
    var dataClient: DataClient = null
    private final val BGP_PORT_NAME: String = "midobgp%d"
    private var zebra: ZebraServer = null
    private var bgpd: BgpConnection = null

    private var bgpPortIdx = 0
    private var run = false


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

        case bgp: BGPLink =>
            handleBGPLink(bgp)

        case BGPLinkDeleted(bgpID) =>

    }

    /**
     * We can consider that these things have been created beforehand:
     * - ExteriorRouterPort
     * - Datapath port has been created to match the ExteriorRouterPort
     * - OS Interface has been configured
     */
    def handleBGPLink(bgp: BGPLink) {
        // TODO: use the portID to get the mac of the interface that the portID is bound to.
        var mac: MAC = null
        //var bgpPort: ExteriorRouterPort = null

        implicit val timeout = Timeout(5 seconds)

        val future = VirtualTopologyActor.getRef() ? PortRequest(bgp.portID, update = false)
        future onSuccess  {
            case bgpPort: ExteriorRouterPort => mac = bgpPort.portMac
            case _ => // failure
        }


        // Create a new internal port name
        val portName = BGP_PORT_NAME.format(bgpPortIdx)
        // The length of interface names are limited to 16 bytes.
        if (portName.length() > 16) {
            throw new RuntimeException(
                "The name of the bgp service port is too long")
        }
        log.info("Adding internal port {} for BGP link", portName)


        val internalPort = new InternalPort(portName)
        val futureInternalPort = DatapathController.getRef() ? CreatePortInternal(internalPort, None)
        futureInternalPort onSuccess {
            // what's the return type from CreatePortInternal? we already have
            // created an InternalPort.
            // The internal port must be set
            case _ => // failure
        }

        // what are local and remote TPorts?
        setBGPFlows(bgp.localPort, bgp.peerPort, bgp.localAS, bgp.peerAS,
            localTport = 1, remoteTport = 1)

        // -- configure the interface with the BGP port's ip address
        //TODO(abel)

        // -- launch bgpd
        val bgpConfig = new BgpConfig(bgp.portID,
            bgp.localAS, Net.convertIntToInetAddress(bgp.peerAddr.getAddress), bgp.peerAS)

        // where to get the local address?
        val localAddr: Int = 0
        launchBGPd(bgp.bgpID, bgpConfig, localAddr)

        bgpPortIdx += 1
    }

    def setBGPFlows(localPortNum: Short, remotePortNum: Short,
                        localAddr: Int, remoteAddr: Int,
                        localTport: Short, remoteTport: Short) {

        // call datapath controller to set the BGP flows
    }

    def launchBGPd(bgpId: UUID, bgpConfig: BgpConfig, localAddr: Int) {
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
                bgpd.create(Net.convertIntToInetAddress(localAddr),
                    bgpId, bgpConfig)
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
