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

package org.midonet.cluster.tools

import java.lang.{Short => JShort}
import java.util
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.control.NonFatal

import rx.Subscription

import org.midonet.cluster.data.vtep.model.{MacLocation, VtepMAC}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.{OvsdbVtepConnection, OvsdbVtepDataClient}
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction0, makeAction1}

object VtepCli extends App {

    import scala.concurrent.ExecutionContext.Implicits.global

    private val CommandSeparators = Array(' ', '\t', '\n')
    private val Timeout = 20 seconds

    type Run = PartialFunction[Array[String], Unit]
    trait Command {
        def run: Run
        def help: String = "Unknown command arguments"
    }
    case class Error(str: String) extends Exception(str)

    private var vtep: Option[OvsdbVtepDataClient] = None
    private var stateSubscription: Subscription = null
    private var localMacSubscription: Subscription = null
    private val commands = new mutable.HashMap[String, Command]

    commands += "quit" -> new Command {
        override def run: Run = {
            case _ if vtep.isDefined =>
                disconnect()
                System exit 0
            case _ => System exit 0
        }
    }
    commands += "connect" -> new Command {
        override def run: Run = {
            case _ if vtep.isDefined =>
                throw Error("Already connected to a VTEP, disconnect first.")
            case args if args.length == 2 =>
                connect(IPv4Addr(args(0)), args(1).toInt, 0 seconds, 0)
            case args if args.length == 4 =>
                connect(IPv4Addr(args(0)), args(1).toInt, args(2).toInt.millis,
                        args(3).toInt)
        }
        override val help =
            """
              | CONNECT <address> <port>
              | CONNECT <address> <port> <retry-interval-ms> <max-retries>
            """.stripMargin
    }
    commands += "disconnect" -> new Command {
        override def run: Run = {
            case _ if vtep.nonEmpty =>
                println(s"Disconnecting from VTEP ${vtep.get.endPoint}\n")
                disconnect()
        }
    }
    commands += "ps" -> new Command {
        override def run: Run = {
            case _ if vtep.nonEmpty =>
                println(s"${vtep.get.physicalSwitch.await(Timeout)}\n")
        }
    }
    commands += "ls" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 0 =>
                val logicalSwitches = vtep.get.logicalSwitches.await(Timeout)
                for (logicalSwitch <- logicalSwitches)
                    println(s"$logicalSwitch\n")
            case args if vtep.nonEmpty && args.length == 1 =>
                val logicalSwitch = vtep.get.logicalSwitch(args(0)).await(Timeout)
                println(s"$logicalSwitch\n")
        }
    }
    commands += "pp" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 0 =>
                val physicalPorts = vtep.get.physicalPorts.await(Timeout)
                for (physicalPort <- physicalPorts)
                    println(s"$physicalPort\n")
            case args if vtep.nonEmpty && args.length == 1 =>
                val physicalPort = vtep.get.physicalPort(UUID.fromString(args(0)))
                                           .await(Timeout)
                println(s"$physicalPort\n")
        }
    }
    commands += "add-ls" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 2 =>
                val logicalSwitch =
                    vtep.get
                        .createLogicalSwitch(args(0), Integer.valueOf(args(1)))
                        .await(Timeout)
                println(s"$logicalSwitch\n")
        }
    }
    commands += "del-ls" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 1 =>
                val ls = vtep.get.logicalSwitch(args(0)).await(Timeout)
                             .getOrElse(throw new Exception(
                                  s"Logical switch ${args(0)} not found"))
                vtep.get.deleteLogicalSwitch(ls.uuid).await(Timeout)
                println(s"${vtep.get.logicalSwitch(args(0)).await(Timeout)}\n")
        }
    }
    commands += "add-bind" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length >= 1 =>
                val ls = vtep.get.logicalSwitch(args(0)).await(Timeout).get
                val bindings = for (index <- 1 until args.length by 2)
                    yield (args(index),
                        JShort.parseShort(args(index + 1)))
                val count =
                    vtep.get.addBindings(ls.uuid, bindings).await(Timeout)
                println(s"$count physical ports updated\n")
        }
    }
    commands += "set-bind" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length >= 1 =>
                val ls = vtep.get.logicalSwitch(args(0)).await(Timeout).get
                val bindings = for (index <- 1 until args.length by 2)
                    yield (args(index),
                        JShort.parseShort(args(index + 1)))
                val count =
                    vtep.get.setBindings(ls.uuid, bindings).await(Timeout)
                println(s"$count physical ports updated\n")
        }
    }
    commands += "clear-bind" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 1 =>
                val ls = vtep.get.logicalSwitch(args(0)).await(Timeout).get
                val count = vtep.get.clearBindings(ls.uuid).await(Timeout)
                println(s"$count physical ports updated\n")
        }
    }
    commands += "mac-local" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 4 =>
                val mac = VtepMAC.fromString(args(0))
                val ip = IPv4Addr(args(1))
                val lsName = args(2)
                val tunnelIp = IPv4Addr(args(3))
                val macLocation = MacLocation(mac, ip, lsName, tunnelIp)
                vtep.get.macLocalUpdater.await(Timeout).onNext(macLocation)
            case args if vtep.nonEmpty && args.length == 3 =>
                val mac = VtepMAC.fromString(args(0))
                val ip = IPv4Addr(args(1))
                val lsName = args(2)
                val macLocation = MacLocation(mac, ip, lsName, null)
                vtep.get.macLocalUpdater.await(Timeout).onNext(macLocation)
            case _ if vtep.nonEmpty =>
                val macs = vtep.get.currentMacLocal.await(Timeout)
                for (mac <- macs)
                    println(s"$mac\n")
        }
    }
    commands += "mac-remote" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 4 =>
                val mac = VtepMAC.fromString(args(0))
                val ip = IPv4Addr(args(1))
                val lsName = args(2)
                val tunnelIp = IPv4Addr(args(3))
                val macLocation = MacLocation(mac, ip, lsName, tunnelIp)
                vtep.get.macRemoteUpdater.await(Timeout).onNext(macLocation)
            case args if vtep.nonEmpty && args.length == 3 =>
                val mac = VtepMAC.fromString(args(0))
                val ip = IPv4Addr(args(1))
                val lsName = args(2)
                val macLocation = MacLocation(mac, ip, lsName, null)
                vtep.get.macRemoteUpdater.await(Timeout).onNext(macLocation)
            case _ if vtep.nonEmpty =>
                val macs = vtep.get.currentMacRemote.await(Timeout)
                for (mac <- macs)
                    println(s"$mac\n")
        }
    }

    runInteractive()

    private def runInteractive(): Unit = {
        var index = 0
        do {
            val info = vtep.map(_.endPoint.toString).getOrElse("none")
            val text = StdIn.readLine(s"vtep[$info] ($index)> ")
            try {
                index += runCommand(text)
            } catch {
                case NonFatal(e) =>
                    println(s"ERROR: Command failed ${e.getMessage}\n")
            }
        } while (true)
    }

    private def runCommand(text: String): Int = {
        val args = text.split(CommandSeparators).filter(_.nonEmpty)
        if (0 == args.length) return 0
        val name = args(0).toLowerCase
        val command = commands.getOrElse(name,
                                         throw Error(s"Command `$name` unknown"))
        command.run.applyOrElse(
            util.Arrays.copyOfRange(args, 1, args.length),
            (_: Array[String]) => {
                println(s"Command `$name` syntax is:${command.help}")
                return 0
            })
        1
    }

    private def connect(address: IPv4Addr, port: Int, retryInterval: Duration,
                        maxRetries: Int): Unit = {
        println(s"Connecting to VTEP $address:$port (retries every " +
                s"$retryInterval up to $maxRetries)\n")
        val connection = new OvsdbVtepConnection(address, port, retryInterval,
                                                 maxRetries)
        val client = OvsdbVtepDataClient(connection)
        client.connect().await(Timeout)

        vtep = Some(client)
        stateSubscription = vtep.get.observable.subscribe(
            makeAction1 { state => println(s"VTEP state $state\n") },
            makeAction1 { e => println(s"VTEP error ${e.getMessage}") },
            makeAction0 { })
        localMacSubscription = vtep.get.macLocalUpdates.subscribe(
            makeAction1 { ml => println(s"Local MAC: $ml") },
            makeAction1 { e => println(s"Local MAC error ${e.getMessage}") },
            makeAction0 { })

        println("Connect succeeded\n")
    }

    private def disconnect(): Unit = {
        vtep.get.close().await(Timeout)
        vtep = None

        stateSubscription.unsubscribe()
        stateSubscription = null
        localMacSubscription.unsubscribe()
        localMacSubscription = null
        println("Disconnect succeeded\n")
    }

}
