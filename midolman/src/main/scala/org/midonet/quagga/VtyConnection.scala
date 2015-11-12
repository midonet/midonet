/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.quagga

import java.io._
import java.net.{UnknownHostException, Socket}

import scala.annotation.tailrec
import scala.collection.immutable.Queue

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPAddr}
import org.midonet.quagga.BgpdConfiguration.{Neighbor, BgpdRunningConfig}

import scala.collection.mutable.ListBuffer

object VtyConnection {
    class NotConnectedException extends Exception

    abstract class Script {
        def commands: List[String]
    }

    case class FlatScript(override val commands: List[String]) extends Script

    case class NestingScript(open: String, inner: Script = NoOp) extends Script {
        override def commands = open :: inner.commands ::: List("exit")
    }

    val NoOp = FlatScript(Nil)

    def ConfigureScript(cmd: Script) = NestingScript("configure terminal", cmd)

    val SetExecTimeout: Script = {
        ConfigureScript(
            NestingScript("line vty",
                FlatScript(List("exec-timeout 0")))
        )
    }

}

/**
 * A pure Scala implementation of the Quagga Vty protocol used to configure
 * routing protocols like BGP, OSPF and RIP.
 */
abstract class VtyConnection(val addr: String, val port: Int) extends Closeable {
    import VtyConnection._

    protected def log: Logger

    var socket: Socket = _
    var out: PrintWriter = _
    var in: BufferedReader = _

    private var disconnectCallbacks: List[(Exception) => Unit] = Nil

    protected val prompt = "# "

    private def requireConnection(): Unit = {
        if (!isConnected) {
            log.error("VTY is not connected")
            throw new NotConnectedException()
        }
    }

    def onDisconnect(cb: (Exception) => Unit): Unit = {
        disconnectCallbacks ::= cb
    }

    override def close(): Unit = {
        try {
            if (socket ne null)
                socket.close()
            if (in ne null)
                in.close()
            if (out ne null)
                out.close()
        } catch {
            case e: IOException =>
                log.error("Failed to close vty socket", e)
        } finally {
            socket = null
            in = null
            out = null
        }
    }

    def open() {
        try {
            socket = new Socket(addr, port)
            socket.setSoTimeout(1000)
            out = new PrintWriter(socket.getOutputStream, true)
            in = new BufferedReader(new InputStreamReader(socket.getInputStream))
            initializationSequence()
            log.info(s"opened vty connection to $addr:$port")
        } catch {
            case e: IOException =>
                log.error("Could not open VTY connection", e)
                close()
                throw e
            case e: UnknownHostException =>
                log.error("Could not open VTY connection", e)
                close()
                throw e
        }
    }

    protected def initializationSequence(): Unit = {
        drainUntil((s) => s.endsWith("User Access Verification"))
        out.println("zebra_password")
        out.println()
        drainUntil((s) => s.endsWith("bgpd>") || s.endsWith("bgpd#"))
        exec("enable")
        exec("terminal length 0")
        exec(SetExecTimeout)
    }

    protected def drainUntil(cond: (String) => Boolean): Unit = {
        while (!cond(in.readLine().trim)) {}
    }

    @tailrec
    protected final def collectUntilPrompt(output: Queue[String] = Queue.empty): Queue[String] = {
        val outputLine = in.readLine()
        if (outputLine.endsWith(prompt)) {
            output
        } else {
            log.debug(s"$outputLine")
            collectUntilPrompt(output :+ outputLine)
        }
    }

    def exec(command: String): Seq[String] = {
        requireConnection()
        try {
            out.println(command)
            out.println()
            collectUntilPrompt()
        } catch {
            case e: Exception =>
                log.warn(s"vty command '$command' failed", e)
                disconnectCallbacks.foreach(_(e))
                throw e
        }
    }

    def exec(command: Script): Seq[(String, Seq[String])] = {
        requireConnection()
        command.commands map {
            cmd => (cmd, exec(cmd))
        }
    }

    def isConnected: Boolean = (socket ne null) && (out ne null) && (in ne null)
}

trait BgpConnection {
    def setAs(as: Int)

    def setRouterId(as: Int, localAddr: IPAddr)

    def addPeer(as: Int, peerAddr: IPAddr, peerAs: Int, keepAliveSecs: Int,
                holdTimeSecs: Int, connectRetrySecs: Int)

    def addPeer(as: Int, neigh: Neighbor)

    def deletePeer(as: Int, peerAddr: IPAddr)

    def addNetwork(as: Int, cidr: IPv4Subnet)

    def deleteNetwork(as: Int, cidr: IPv4Subnet)

    def setDebug(enabled: Boolean)

    def showGeneric(cmd: String) : Seq[String]

    def showConfig(): BgpdRunningConfig

    def setMaximumPaths(as: Int, paths: Int)
}

class BgpVtyConnection(addr: String, port: Int) extends VtyConnection(addr, port)
        with BgpConnection {

    override val log = Logger(LoggerFactory.getLogger("org.midonet.routing.bgp.bgp-vty"))

    import VtyConnection._

    private def SetAs(as: Int)(subcommands: List[String]): Script = {
        ConfigureScript(
            NestingScript(s"router bgp $as",
                FlatScript(subcommands)))
    }

    private def SetAs(as: Int, subcommand: String): Script = SetAs(as)(List(subcommand))

    override def setAs(as: Int) {
        exec(SetAs(as){ List(
            s"bgp bestpath as-path multipath-relax")
        })
        exec(ConfigureScript(
                FlatScript(
                    List("ip as-path access-list 1 permit ^$"))))
    }

    override def setMaximumPaths(as: Int, paths: Int): Unit = {
        exec(SetAs(as){ List(
            s"maximum-paths $paths")
        })
    }

    override def setRouterId(as: Int, localAddr: IPAddr) {
        exec(SetAs(as, s"bgp router-id $localAddr"))
    }

    override def addPeer(as: Int, peer: IPAddr, peerAs: Int, keepAliveSecs: Int,
                         holdTimeSecs: Int, connectRetrySecs: Int) {
        exec(SetAs(as){ List(
            s"neighbor $peer remote-as $peerAs",
            s"neighbor $peer timers $keepAliveSecs $holdTimeSecs",
            s"neighbor $peer timers connect $connectRetrySecs",
            s"neighbor $peer filter-list 1 out")
        })
    }

    override def addPeer(as: Int, neigh: Neighbor) {
       var cmds = List(s"neighbor ${neigh.address} remote-as ${neigh.as}",
                       s"neighbor ${neigh.address} filter-list 1 out")

        for (keepalive <- neigh.keepalive ; holdtime <- neigh.holdtime) {
            cmds :+= s"neighbor ${neigh.address} timers $keepalive $holdtime"
        }

        for (retry <- neigh.connect) {
            cmds :+= s"neighbor ${neigh.address} timers connect $retry"
        }

        for (password <- neigh.password) {
            cmds :+= s"neighbor ${neigh.address} password $password"
        }

        exec(SetAs(as)(cmds))
    }

    override def deletePeer(as: Int, peerAddr: IPAddr) {
        exec(SetAs(as, s"no neighbor $peerAddr"))
    }

    override def addNetwork(as: Int, cidr: IPv4Subnet) {
        exec(SetAs(as, s"network $cidr"))
    }

    override def deleteNetwork(as: Int, cidr: IPv4Subnet) {
        exec(SetAs(as, s"no network $cidr"))
    }

    override def setDebug(enabled: Boolean) {
        val cmd = if (enabled) "debug bgp"
                  else "no debug bgp"
        exec(ConfigureScript(
                FlatScript(List(cmd))))
    }

    override def showConfig(): BgpdRunningConfig = {
        BgpdRunningConfig().build(exec("show run").toList)
    }

    override def showGeneric(cmd : String) : Seq[String] = exec(cmd)
}
