/**
 * VtyConnection.scala - Quagga VTY connection management classes.
 *
 * A pure Scala implementation of the Quagga Vty protocol used to configure
 * routing protocols like BGP, OSPF and RIP.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.quagga

import com.midokura.midolman.state.{AdRouteZkManager, BgpZkManager}
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig
import com.midokura.midolman.state.BgpZkManager.BgpConfig
import com.midokura.midolman.state.NoStatePathException
import com.midokura.midolman.layer3.Route

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, Set}
import scala.util.matching.Regex

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.net.{InetAddress,Socket,SocketException}
import java.util.UUID

import org.slf4j.LoggerFactory


/**
 * Static methods and constants for VtyConnection.
 */
object VtyConnection {
    private final val BufSize = 1024
    private final val SkipHello = 7

    private final val Enable		= "enable"
    private final val Disable		= "disable"
    private final val ConfigureTerminal = "configure terminal"
    private final val Exit		= "exit"
    private final val End		= "end"

    private  final val log = LoggerFactory.getLogger(this.getClass)
}

/**
 * Interfaces for VtyConnection.
 */
abstract class VtyConnection(val addr: String, val port: Int,
                             val password: String) {
    import VtyConnection._

    var socket: Socket = _
    var out: PrintWriter = _
    var in: BufferedReader = _
    var connected = false

    private def sendMessage(command: String) {
        out.println(command)
    }

    private def recvMessage(): Seq[String] = {
        var lines = new ListBuffer[String]()
        var line: String = null
        while ({line = in.readLine; line != null }) {
            lines.append(line)
            //println(line)
        }
        return lines.toSeq
    }

    private def dropMessage() {
        // TODO(yoshi): handle exceptions
        in.readLine
    }

    private def openConnection() {
        socket = new Socket(addr, port)
        out = new PrintWriter(socket.getOutputStream(), true)
        in = new BufferedReader(new InputStreamReader(socket.getInputStream),
                                BufSize)

        // Quagga returns, blank line, hello message, two blank lines,
        // user access verification message and blank line upon connection.
        for (i <- 0 until SkipHello if in.ready) {
            dropMessage
        }
        sendMessage(password)
        // Consume password echo back.
        dropMessage
        enable
        connected = true
    }

    private def closeConnection() {
        connected = false
        out.close
        in.close
    }

    protected def doTransacation(messages: Seq[String],
                      isConfigure: Boolean): Seq[String] = {
        openConnection
        if (isConfigure) {
            configureTerminal
        }

        for (message <- messages) {
            sendMessage(message)
            log.info("doTransaction: %s".format(message))
        }

        if (isConfigure) {
            end
        }
        // Send exit here to get EOF on read.
        exit

        val response = recvMessage
        closeConnection
        return response
    }

    protected def isConnected(): Boolean = { return connected }

    protected def enable() {
        sendMessage(Enable)
        dropMessage
    }

    protected def disable() {
        sendMessage(Disable)
        dropMessage
    }

    protected def configureTerminal() {
        sendMessage(ConfigureTerminal)
        dropMessage
    }

    protected def exit() {
        sendMessage(Exit)
    }

    protected def end() {
        sendMessage(End)
    }
}


/**
 * Static methods and constants for BgpVtyConnection.
 */
object BgpVtyConnection {
    private final val GetAs = "show run"
    private final val GetAsRegex = """router bgp (\d+)""".r
    private final val SetAs = "router bgp %s"
    private final val DeleteAs = "no router bgp %s"
    private final val SetLocalNw = "bgp router-id %s"
    private final val SetPeer = "neighbor %s remote-as %d"
    private final val GetNetwork = "show ip bgp"
    // The first regex ^[sdh\*>irSR]* expects the following status codes:
    // s suppressed, d damped, h history, * valid, > best, i internal,
    // r RIB-failure, S Stale, R Removed
    private final val GetNetworkRegex =
        """^[sdh\*>irSR]*\s*([\d\./]*)\s*([\d\.]*)\s*[\d\.]*\s*([\d\.]*)\s*(.)$""".r
    private final val SetNetwork = "network %s/%d"
    private final val DeleteNetwork = "no network %s/%d"

    private  final val log = LoggerFactory.getLogger(this.getClass)
}

/**
 * Interfaces for BgpConfig.
 */
trait BgpConnection {
    def create(localAddr: InetAddress, bgpUUID: UUID, bgp: BgpConfig)
    def getAs(): Int
    def setAs(as: Int)
    def deleteAs(as: Int)
    def setLocalNw(as: Int, localAddr: InetAddress)
    def setPeer(as: Int, peerAddr: InetAddress, peerAs: Int)
    def getNetwork(): Seq[String]
    def setNetwork(as: Int, nwPrefix: String, prefixLength: Int)
    def deleteNetwork(as: Int, nwPrefix: String, prefixLength: Int)
}

class BgpVtyConnection(addr: String, port: Int, password: String,
                       val bgpZk: BgpZkManager,
                       val adRouteZk: AdRouteZkManager)
extends VtyConnection(addr, port, password) with BgpConnection {
    import BgpVtyConnection._

    private class AdRouteWatcher(val localAS: Int, val adRouteUUID: UUID, 
                                 val oldConfig: AdRouteConfig,
                                 val adRouteZk: AdRouteZkManager)
            extends Runnable {
                override def run() {
                    // Whether this event is update or delete, we have to
                    // delete the old config first.
                    deleteNetwork(localAS, oldConfig.nwPrefix.getHostAddress,
                                  oldConfig.prefixLength)
                    try {
                        val adRoute = adRouteZk.get(adRouteUUID, this)
                        if (adRoute != null) {
                            setNetwork(localAS, adRoute.nwPrefix.getHostAddress,
                                       adRoute.prefixLength)
                        }
                    } catch {
                        case e: NoStatePathException =>
                            { log.warn("AdRouteWatcher: node already deleted") }
                    }
                }
            }

    private class BgpWatcher(val localAddr: InetAddress, var bgpUUID: UUID,
                             var oldConfig: BgpConfig, val adRoutes: Set[UUID],
                             val bgpZk: BgpZkManager,
                             val adRouteZk: AdRouteZkManager)
            extends Runnable {
                override def run() {
                    // Compare the length of adRoutes and only handle
                    // adRoute events when routes are added.
                    try {
                        if (adRoutes.size < adRouteZk.list(bgpUUID).size) {
                            val bgp = bgpZk.get(bgpUUID, this)
                            if (bgp != null) {
                                this.bgpUUID = bgpUUID
                                this.oldConfig = bgp
                                create(localAddr, bgpUUID, bgp)
                            }
                        }
                    } catch {
                        case e: NoStatePathException => {
                            log.warn("BgpWatcher: node already deleted")
                            deleteAs(oldConfig.localAS)
                        }
                    }
                }
            }

    override def getAs(): Int = {
        val request = new ListBuffer[String]()
        var response: Seq[String] = null

        request += GetAs

        try {
            response = doTransacation(request.toSeq, false)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed getting local AS", e) }
        }

        if (response != null) {
            for (item <- response) {
                for (rmatch <- GetAsRegex.findFirstMatchIn(item)) {
                    // Extract the AS number by accessing first match.
                    val as = java.lang.Integer.parseInt(rmatch.group(1))
                    return as
                }
            }
        }

        return 0
    }

    override def setAs(as: Int) {
        val request = new ListBuffer[String]()
        request += SetAs.format(as)

        try {
            doTransacation(request.toSeq, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting local AS", e) }
        }
    }

    override def deleteAs(as: Int) {
        val request = new ListBuffer[String]()
        request += DeleteAs.format(as)

        try {
            doTransacation(request.toSeq, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed deleting local AS", e) }
        }
    }    

    override def setLocalNw(as: Int, localAddr: InetAddress) {
        val request = ListBuffer[String]()
        request += SetAs.format(as)
        request += SetLocalNw.format(localAddr.getHostAddress)

        try {
            doTransacation(request.toSeq, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting local network", e) }
        }
    }

    override def setPeer(as: Int, peerAddr: InetAddress, peerAs: Int) {
        val request = ListBuffer[String]()
        request += SetAs.format(as)
        request += SetPeer.format(peerAddr.getHostAddress, peerAs)

        try {
            doTransacation(request.toSeq, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting local network", e) }
        }
    }

    def getPeerNetwork(): Seq[(String, String, String, String)] = {
        val request = new ListBuffer[String]()
        var response: Seq[String] = null

        request += GetNetwork

        try {
            response = doTransacation(request.toSeq, false)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => { 
                log.error("failed getting network", e)
            }
        }

        var peerNetworks = new ListBuffer[(String, String, String, String)]()
        for (item <- response) {
            //log.debug("getPeerNetwork: item {}", item)
            for (rmatch <- GetNetworkRegex.findFirstMatchIn(item)) {
                val network = new ListBuffer[String]
                // nwPrefix
                network += rmatch.group(1)
                // nextHop
                network += rmatch.group(2)
                // weight
                network += rmatch.group(3)
                // path
                network += rmatch.group(4)

                val peerNetwork = network.mkString(",")
                log.debug("getPeerNetwork: peerNetwork {}", peerNetwork)
                //peerNetworks += peerNetwork
                peerNetworks += ((rmatch.group(1),  // nwPrefix
                                  rmatch.group(2),  // nextHop
                                  rmatch.group(3),  // weight
                                  rmatch.group(4))) // path
            }
        }
        return peerNetworks.toSeq
    }

    override def getNetwork(): Seq[String] = {
        var networks = new ListBuffer[String]()
        for (peerNetwork <- getPeerNetwork) {
            // If the next hop is 0.0.0.0, it should be the network we're
            // advertising.
            if (peerNetwork._2.equals("0.0.0.0")) {
                networks += peerNetwork._1
                // NB: nwPrefix doesn't contain prefix length if Quagga can
                // guess it by default. e.g. 192.168.X.0/24 will show up as
                // 192.168.X.0 only, and 10.0.0.0/8 as 10.0.0.0 as well.
                log.debug("getNetwork: {}", peerNetwork._1)
            }
        }
        return networks.toSeq
    }

    override def setNetwork(as: Int, nwPrefix: String, prefixLength: Int) {
        val request = new ListBuffer[String]()
        request += SetAs.format(as)
        request += SetNetwork.format(nwPrefix, prefixLength)

        try {
            doTransacation(request.toSeq, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting advertising routes", e) }
        }
    }

    override def deleteNetwork(as: Int, nwPrefix: String,
                               prefixLength: Int) = {
        if (getAs != 0) {
            val request = new ListBuffer[String]()
            request += SetAs.format(as)
            request += DeleteNetwork.format(nwPrefix, prefixLength)

            try {
                doTransacation(request.toSeq, true)
            } catch {
                // TODO(yoshi): finer exception handling.
                case e: Exception =>
                    { log.error("failed deleting advertising routes", e) }
            }
        }
    }

    override def create(localAddr: InetAddress, bgpUUID: UUID,
                        bgp: BgpConfig) {
        setAs(bgp.localAS)
        setLocalNw(bgp.localAS, localAddr)
        setPeer(bgp.localAS, bgp.peerAddr, bgp.peerAS)

        val adRoutes = Set[UUID]()
        val bgpWatcher = new BgpWatcher(localAddr, bgpUUID, bgp, adRoutes,
                                        bgpZk, adRouteZk)

        for (adRouteUUID <- adRouteZk.list(bgpUUID, bgpWatcher)) {
            val adRoute = adRouteZk.get(adRouteUUID)
            setNetwork(bgp.localAS, adRoute.nwPrefix.getHostAddress,
                       adRoute.prefixLength)
            adRoutes.add(adRouteUUID)
            // Register AdRouteWatcher.
            adRouteZk.get(adRouteUUID,
                          new AdRouteWatcher(bgp.localAS, adRouteUUID, adRoute,
                                             adRouteZk))
        }
    }
}
