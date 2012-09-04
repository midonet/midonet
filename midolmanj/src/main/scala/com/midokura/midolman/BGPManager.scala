/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman

import akka.actor.{ActorLogging, Actor}
import com.midokura.midonet.cluster.DataClient
import com.google.inject.Inject
import java.util.UUID
import com.midokura.util.functors.Callback2
import topology.VirtualTopologyActor
import topology.VirtualTopologyActor.{BGPLinkDeleted, BGPListUnsubscribe, BGPListRequest}
import com.midokura.midonet.cluster.client.BGPLink
import java.io.File
import org.newsclub.net.unix.{AFUNIXSocketAddress, AFUNIXServerSocket}
import com.midokura.quagga.{ZebraServer, BgpConnection, BgpVtyConnection, ZebraServerImpl}

object BGPManager extends Referenceable {
    val Name = "BGPManager"
    case class LocalPortActive(portID: UUID, active: Boolean)
}

class BGPManager extends Actor with ActorLogging {
    import BGPManager._

    @Inject
    var dataClient: DataClient = null;
    private final val BGP_TCP_PORT: Short = 179
    private final val BGP_PORT_NAME: String = "midobgp%d"
    private var zebra: ZebraServer = null
    private var bgpd: BgpConnection = null


    private var bgpPortIdx = 0;


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
        val socketFile = new File("/var/run/quagga/zserv.api");
        val socketDir = socketFile.getParentFile();
        if (!socketDir.exists()) {
            socketDir.mkdirs();
            // Set permission to let quagga daemons write.
            socketDir.setWritable(true, false);
        }

        if (socketFile.exists())
            socketFile.delete();

        val server = AFUNIXServerSocket.newInstance();
        val address = new AFUNIXSocketAddress(socketFile);

        dataClient.subscribeToLocalActivePorts(localPortsCB)
        val zebraServer = new ZebraServerImpl(server, address,
                                                      null, null, null);
        val vtyConnection = new BgpVtyConnection("localhost",
                                    2605, "zebra", null, null);


    }

    override def receive = {
        case LocalPortActive(portID, active) =>
            // Register/unregister for update on this port's BGP list.
            if(active)
                VirtualTopologyActor.getRef() ! BGPListRequest(portID, true)
            else {
                VirtualTopologyActor.getRef() ! BGPListUnsubscribe(portID)
                // TODO(pino): tear down any BGPs for this port.
            }

        case bgp: BGPLink =>
            handleBGPLink(bgp)

        case BGPLinkDeleted(bgpID) =>

    }

    def handleBGPLink(bgp: BGPLink) {
        // Create tap for the bgpd (e.g. midobgp0)
        // Add the tap as an internal port on the datapath (call DatapathCtrl)
        // When the answer comes back successful:
        // -- install a bunch flows for BGP
        // -- configure the tap (address, mac, etc)
        // -- launch bgpd
        val portName = BGP_PORT_NAME.format(bgpPortIdx)
        // The length of interface names are limited to 16 bytes.
        if (portName.length() > 16) {
            throw new RuntimeException(
                "The name of the service port is too long");
        }

        log.info("Adding internal port {} for BGP link", portName)
        // TODO(pino): replace OVS calls with DatapathController messages.
        //PortBuilder portBuilder = ovsdb.addInternalPort(datapathId,
        //    portName);
        //portBuilder.externalId(portIdExtIdKey, portId.toString());
        // TODO: remember that external IDs are no longer needed.
        //portBuilder.externalId(portServiceExtIdKey, BGP_SERVICE_EXT_ID);
        // TODO: the mac does have to match up with the uplink's MAC.
        //if (mac != null) {
        //    portBuilder.ifMac(mac.toString());
        //}
        // If there is an existing service port, ovs will return False.
        //portBuilder.build();

        bgpPortIdx += 1;
    }


}
