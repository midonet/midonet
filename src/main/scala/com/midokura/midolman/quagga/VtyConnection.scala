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

    private def sendMessage(command: String) = {
        out.println(command)
    }

    def recvMessage(): Iterator[String] = {
        var lines = new ListBuffer[String]()
        var line: String = null
        while ({line = in.readLine; line != null }) {
            lines.append(line)
            //println(line)
        }
        return lines.iterator
    }

    def dropMessage() = {
        // TODO(yoshi): handle exceptions
        in.readLine
    }

    def openConnection()  {
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

    def closeConnection() = {
        connected = false
        out.close
        in.close
    }

    def doTransacation(messages: Iterator[String],
                      isConfigure: Boolean): Iterator[String] = {
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

    def isConnected(): Boolean = { return connected }

    def enable() = {
        sendMessage(Enable)
        dropMessage
    }

    def disable() = {
        sendMessage(Disable)
        dropMessage
    }

    protected def configureTerminal() = {
        sendMessage(ConfigureTerminal)
        dropMessage
    }

    protected def exit() = {
        sendMessage(Exit)
    }

    protected def end() = {
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
    private final val GetNetworkRegex =
        """\*> ([\d\./]*)\s*([\d\.]*)\s*[\d\.]*\s*([\d\.]*)\s*([\w]*)""".r
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
    def getNetwork()
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
                override def run() = {
                    // Whether this event is update or delete, we have to
                    // delete the old config first.
                    deleteNetwork(localAS, oldConfig.nwPrefix.getHostAddress,
                                  oldConfig.prefixLength)
                    try {
                        val newNode = adRouteZk.get(adRouteUUID, this)
                        if (newNode != null) {
                            val adRoute = newNode.value
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
                override def run() = {
                    // Compare the length of adRoutes and only handle
                    // adRoute events when routes are added.
                    try {
                        if (adRoutes.size < adRouteZk.list(bgpUUID).size) {
                            val newNode = bgpZk.get(bgpUUID, this)
                            if (newNode != null) {
                                val bgpUUID = newNode.key
                                val bgp = newNode.value
                                this.bgpUUID = newNode.key
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

    private class PeerNetwork(val nwPrefix: String, val nextHop: String,
                              val weight: Int, val path: String) {
        override def toString(): String = {
            val string = new ListBuffer[String]
            string += this.nwPrefix
            string += this.nextHop
            string += this.weight.toString
            string += this.path
            return string.mkString(",")
        }
    }

    override def getAs(): Int = {
        val request = new ListBuffer[String]()
        var response: Iterator[String] = null

        request += GetAs

        try {
            response = doTransacation(request.iterator, false)
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

    override def setAs(as: Int) = {
        val request = new ListBuffer[String]()
        request += SetAs.format(as)

        try {
            doTransacation(request.iterator, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting local AS", e) }
        }
    }

    override def deleteAs(as: Int) = {
        val request = new ListBuffer[String]()
        request += DeleteAs.format(as)

        try {
            doTransacation(request.iterator, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed deleting local AS", e) }
        }
    }    

    override def setLocalNw(as: Int, localAddr: InetAddress) = {
        val request = ListBuffer[String]()
        request += SetAs.format(as)
        request += SetLocalNw.format(localAddr.getHostAddress)

        try {
            doTransacation(request.iterator, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting local network", e) }
        }
    }

    override def setPeer(as: Int, peerAddr: InetAddress, peerAs: Int) = {
        val request = ListBuffer[String]()
        request += SetAs.format(as)
        request += SetPeer.format(peerAddr.getHostAddress, peerAs)

        try {
            doTransacation(request.iterator, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting local network", e) }
        }
    }

    override def getNetwork() = {
        val request = new ListBuffer[String]()
        var response: Iterator[String] = null

        request += GetNetwork

        try {
            response = doTransacation(request.iterator, false)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => { 
                log.error("failed getting network", e)
            }
        }

        if (response != null) {
            var networks = new ListBuffer[PeerNetwork]()
            for (item <- response) {
                for (rmatch <- GetNetworkRegex.findFirstMatchIn(item)) {
                    // TODO(yoshi): write comments
                    val peerNetwork = new PeerNetwork(
                        rmatch.group(1), rmatch.group(2),
                        java.lang.Integer.parseInt(rmatch.group(3)),
                        rmatch.group(4))
                    log.info(peerNetwork.toString)
                    networks += peerNetwork
                }
            }
        }
    }

    override def setNetwork(as: Int, nwPrefix: String, prefixLength: Int) = {
        val request = new ListBuffer[String]()
        request += SetAs.format(as)
        request += SetNetwork.format(nwPrefix, prefixLength)

        try {
            doTransacation(request.iterator, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed setting advertising routes", e) }
        }
    }

    override def deleteNetwork(as: Int, nwPrefix: String,
                               prefixLength: Int) = {
        val request = new ListBuffer[String]()
        request += SetAs.format(as)
        request += DeleteNetwork.format(nwPrefix, prefixLength)

        try {
            doTransacation(request.iterator, true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception =>
                { log.error("failed deleting advertising routes", e) }
        }
    }

    override def create(localAddr: InetAddress, bgpUUID: UUID,
                        bgp: BgpConfig) = {
        setAs(bgp.localAS)
        setLocalNw(bgp.localAS, localAddr)
        setPeer(bgp.localAS, bgp.peerAddr, bgp.peerAS)

        val adRoutes = Set[UUID]()
        val bgpWatcher = new BgpWatcher(localAddr, bgpUUID, bgp, adRoutes,
                                        bgpZk, adRouteZk)

        for (adRouteNode <- adRouteZk.list(bgpUUID, bgpWatcher)) {
            val adRouteUUID = adRouteNode.key
            val adRoute = adRouteNode.value
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
