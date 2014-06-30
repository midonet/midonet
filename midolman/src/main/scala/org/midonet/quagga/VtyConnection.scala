/**
 * VtyConnection.scala - Quagga VTY connection management classes.
 *
 * A pure Scala implementation of the Quagga Vty protocol used to configure
 * routing protocols like BGP, OSPF and RIP.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package org.midonet.quagga


import scala.collection.mutable.ListBuffer

import java.io._
import java.net.{UnknownHostException, Socket}

import org.slf4j.LoggerFactory
import org.midonet.packets.IPAddr
import scala.concurrent.{ExecutionContext, Await, Future}
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration

/**
 * Static methods and constants for VtyConnection.
 */
object VtyConnection {
    private final val Enable = "enable"
    private final val Disable = "disable"
    private final val ConfigureTerminal = "configure terminal"
    private final val Exit = "exit"
    private final val End = "end"

    private final val log = LoggerFactory.getLogger(this.getClass)
}

/**
 * Interfaces for VtyConnection.
 */
abstract class VtyConnection(val addr: String, val port: Int,
                             val password: String, val keepAliveTime: Int,
                             val holdTime: Int, val connectRetryTime: Int) {

    import VtyConnection._

    var socket: Socket = _
    var out: PrintWriter = _
    var in: BufferedReader = _
    var connected = false

    // BufferSize overrideable for testing purposes
    val BufferSize = 4096

    case class NotConnectedException() extends Exception

    protected def openConnection() {
        log.debug("begin, addr: {}, port: {}", addr, port)
        try {
            socket = new Socket(addr, port)
            out = new PrintWriter(socket.getOutputStream, true)
            in = new BufferedReader(new InputStreamReader(socket.getInputStream))

            connected = true
        } catch {
            case e: IOException =>
                log.error("Could not open VTY connection: {}", e)
            case e: UnknownHostException =>
                log.error("Could not open VTP connection: {}", e)
        }
        log.debug("end")
    }

    protected def closeConnection() {
        log.debug("begin")

        connected = false
        out.close()
        in.close()
        log.debug("end")
    }

    private def sendMessage(command: String) {
        log.debug("command: {}", command)

        if (!connected) {
            log.error("VTY is not connected")
            throw new NotConnectedException
        }

        out.println(command)
    }

    private def recvMessage(): Seq[String] = {
        recvMessage(minLines = 0)
    }

    def recvMessage(minLines: Int): Seq[String] = {
        log.debug("begin")
        if (!connected) {
            log.error("VTY is not connected")
            throw new NotConnectedException
        }

        implicit val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

        val future: Future[Seq[String]] = Future {
            var lines: List[String] = List[String]("")
            var lineContinues = false
            do {
                if(in.ready) {
                    val charBuffer = new Array[Char](BufferSize)
                    val count = in.read(charBuffer)

                    if (count > 0) {
                        //log.debug("received a buffer with {} chars.", count)

                        val limitedBuffer = charBuffer.take(count)

                        val stringBuffer = limitedBuffer.mkString
                        //log.debug("stringBuffer: {}", stringBuffer)

                        val controlCode : (Char) => Boolean = (c:Char) =>
                            ((c < 32 || c == 127) &&
                            !sys.props("line.separator").contains(c))
                        val filteredStringBuffer = stringBuffer.filterNot(controlCode)
                        //log.debug("filteredStringBuffer: {}", filteredStringBuffer)

                        val strings = filteredStringBuffer.split(sys.props("line.separator"), -1).toList
                        //log.debug("splitted buffer into {} strings.", strings.size)

                        //strings.foreach(line => log.debug("line {}", line))
                        val fixedLines = lines.take(lines.size - 1)
                        val fixedStrings = strings.takeRight(strings.toList.size - 1)
                        val appendedLine: String = lines.last + strings.head
                        lines = fixedLines ::: List(appendedLine) ::: fixedStrings

                        if (count == BufferSize) {
                            lineContinues = true
                        } else {
                            lineContinues = false
                        }
                    }
                }
            } while(
                (lines.size < minLines) ||
                ((lines.size == minLines) && lineContinues)
            )

            log.debug("end")
            lines.toSeq
        }

        val res = Await.result(future, Duration.apply("1 second"))
        ec.shutdown()
        res
    }

    protected def dropMessage() {
        if (!connected) {
            log.error("VTY is not connected")
            throw new NotConnectedException
        }

        val droppedMessage = recvMessage()
        log.debug("droppedMessage: {}", droppedMessage)
    }

    protected def checkHello() {
        log.debug("begin")
        val PasswordRegex = ".*Password:.*".r
        var versionMatch = false
        val messages = recvMessage(minLines = 6)
        for(message <- messages) {
            message match {
                case "" =>
                    log.debug("empty line")
                case "Hello, this is Quagga (version 0.99.21)." =>
                    log.debug("version match")
                    versionMatch = true
                case "Copyright 1996-2005 Kunihiro Ishiguro, et al." =>
                    log.debug("copyright match") // ok, do nothing
                case "User Access Verification" =>
                    log.debug("UAV match")
                    sendMessage(password) // don't wait for password message
                case PasswordRegex() =>
                    log.debug("password match")
                case s: String =>
                    log.error("bgpd hello message doesn't match expected: \"" +
                        s + "\" size: " + s.size)
            }
        }

        if (versionMatch == false)  {
            log.error("bgpd version didn't match expected.")
        }

        log.debug("end")
}

    protected def doTransaction(messages: Seq[String],
                                 isConfigure: Boolean,
                                 minLines : Int = 0): Seq[String] = {
        openConnection()
        checkHello()
        enable()

        if (isConfigure) {
            configureTerminal()
        }

        for (message <- messages) {
            sendMessage(message)
            log.info("doTransaction: %s".format(message))
        }

        if (isConfigure) {
            end()
        }
        // Send exit here to get EOF on read.
        exit()

        val response = recvMessage(minLines)
        log.debug("response: {}", response)
        closeConnection()
        response
    }

    protected def isConnected: Boolean = {
        connected
    }

    protected def enable() {
        log.debug("begin")
        sendMessage(Enable)
        dropMessage()
    }

    protected def disable() {
        log.debug("begin")
        sendMessage(Disable)
        dropMessage()
    }

    protected def configureTerminal() {
        log.debug("begin")

        sendMessage(ConfigureTerminal)
        dropMessage()
    }

    protected def exit() {
        log.debug("begin")

        sendMessage(Exit)
    }

    protected def end() {
        log.debug("begin")

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
    private final val ConfigurePeerKeepAlive = "neighbor %s timers %s %s"
    private final val ConfigurePeerConnectRetry = "neighbor %s timers connect %s"
    private final val DeletePeer = "no neighbor %s"
    private final val GetNetwork = "show ip bgp"
    // The first regex ^[sdh\*>irSR]* expects the following status codes:
    // s suppressed, d damped, h history, * valid, > best, i internal,
    // r RIB-failure, S Stale, R Removed
    private final val GetNetworkRegex =
        """^[sdh\*>irSR]*\s*([\d\./]*)\s*([\d\.]*)\s*[\d\.]*\s*([\d\.]*)\s*(.)$""".r
    private final val SetNetwork = "network %s/%d"
    private final val DeleteNetwork = "no network %s/%d"
    private final val SetLogFile = "log file %s"
    private final val EnableDebug = "debug bgp"
    private final val DisableDebug = "no debug bgp"
    private final val ShowGeneric = "show ip bgp %s"

    private final val log = LoggerFactory.getLogger(this.getClass)
}

/**
 * Interfaces for BgpConfig.
 */
trait BgpConnection {
    def getAs: Int

    def setAs(as: Int)

    def deleteAs(as: Int)

    def setLocalNw(as: Int, localAddr: IPAddr)

    def setPeer(as: Int, peerAddr: IPAddr, peerAs: Int)

    def deletePeer(as: Int, peerAddr: IPAddr)

    def getNetwork: Seq[String]

    def setNetwork(as: Int, nwPrefix: String, prefixLength: Int)

    def deleteNetwork(as: Int, nwPrefix: String, prefixLength: Int)

    def setLogFile(file: String)

    def setDebug(isEnabled: Boolean)

    def showGeneric(cmd: String) : Seq[String]

}

class BgpVtyConnection(addr: String, port: Int, password: String, keepAliveTime: Int,
                       holdTime: Int, connectRetryTime: Int)
    extends VtyConnection(addr, port, password, keepAliveTime, holdTime, connectRetryTime)
    with BgpConnection {

    import BgpVtyConnection._

    override def getAs: Int = {
        log.debug("begin")

        val request = new ListBuffer[String]()
        var response: Seq[String] = null

        request += GetAs

        try {
            response = doTransaction(request.toSeq, isConfigure = false)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed getting local AS", e)
            }
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

        0
    }

    override def setAs(as: Int) {
        log.debug("begin")

        val request = new ListBuffer[String]()
        request += SetAs.format(as)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed setting local AS", e)
            }
        }
    }

    /*
     * this will delete the entire AS config, including peers and networks
     */
    override def deleteAs(as: Int) {
        log.debug("begin")

        val request = new ListBuffer[String]()
        request += DeleteAs.format(as)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed deleting local AS", e)
            }
        }
    }

    override def setLocalNw(as: Int, localAddr: IPAddr) {
        log.debug("begin")

        val request = ListBuffer[String]()
        request += SetAs.format(as) // this is actually needed
        request += SetLocalNw.format(localAddr.toString)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed setting local network", e)
            }
        }
    }

    override def setPeer(as: Int, peerAddr: IPAddr, peerAs: Int) {
        log.debug("begin")

        val request = ListBuffer[String]()
        request += SetAs.format(as) // this is actually needed
        request += SetPeer.format(peerAddr.toString, peerAs)

        request += ConfigurePeerKeepAlive.format(peerAddr.toString,
            keepAliveTime, holdTime)
        request += ConfigurePeerConnectRetry.format(peerAddr.toString,
            connectRetryTime)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed setting local network", e)
            }
        }
    }

    override def deletePeer(as: Int, peerAddr: IPAddr) {
        log.debug("begin")

        val request = ListBuffer[String]()
        request += SetAs.format(as) // this is actually needed
        request += DeletePeer.format(peerAddr.toString)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed deleting peer", e)
            }
        }
    }

    def getPeerNetwork: Seq[(String, String, String, String)] = {
        log.debug("begin")

        val request = new ListBuffer[String]()
        var response: Seq[String] = null

        request += GetNetwork

        try {
            response = doTransaction(request.toSeq, isConfigure = false)
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
                peerNetworks += ((rmatch.group(1), // nwPrefix
                    rmatch.group(2), // nextHop
                    rmatch.group(3), // weight
                    rmatch.group(4))) // path
            }
        }
        peerNetworks.toSeq
    }

    override def getNetwork: Seq[String] = {
        log.debug("begin")

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
        networks.toSeq
    }

    override def setNetwork(as: Int, nwPrefix: String,
                            prefixLength: Int) {
        log.debug("begin")

        val request = new ListBuffer[String]()
        request += SetAs.format(as) // this is actually needed
        request += SetNetwork.format(nwPrefix, prefixLength)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed setting advertising routes", e)
            }
        }
    }

    override def deleteNetwork(as: Int, nwPrefix: String,
                               prefixLength: Int) {
        log.debug("begin")

        val request = new ListBuffer[String]()
        request += SetAs.format(as) // this is actually needed
        request += DeleteNetwork.format(nwPrefix, prefixLength)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed deleting advertising routes", e)
            }
        }
    }

    override def setLogFile(file: String) {
        log.debug("begin")

        val request = new ListBuffer[String]()
        request += SetLogFile.format(file)

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed setting log file", e)
            }
        }
    }

    override def setDebug(isEnabled: Boolean) {
        log.debug("begin")

        val request = new ListBuffer[String]()
        if (isEnabled)
            request += EnableDebug
        else
            request += DisableDebug

        try {
            doTransaction(request.toSeq, isConfigure = true)
        } catch {
            // TODO(yoshi): finer exception handling.
            case e: Exception => {
                log.error("failed setting debug option", e)
            }
        }
    }

    private def doOp(messages: Seq[String], isConfigure: Boolean,
                     minLines : Int) : Option[Seq[String]] = {
        log.debug("begin {}", messages)

        try {
            Some(doTransaction(messages, isConfigure, minLines))
        } catch {
            case e: Exception => {
                log.error(s"failed running $messages {}", e)
                None
            }
        }
    }

    override def showGeneric(cmd : String) : Seq[String] = {

        doOp(Array[String](ShowGeneric.format(cmd)), false, 2) match {
            case Some(value) =>
                value
            case None =>
                return Array[String]()
        }
    }

}
