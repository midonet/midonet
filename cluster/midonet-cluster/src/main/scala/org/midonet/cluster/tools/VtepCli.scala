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

import java.util
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.control.NonFatal

import rx.Subscription

import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbVtepDataClient
import org.midonet.util.functors.{makeAction0, makeAction1}
import org.midonet.util.concurrent._

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
            case args if vtep.nonEmpty && args.length == 1=>
                println(s"${vtep.get.logicalSwitch(args(0)).await(Timeout)}\n")
        }
    }
    commands += "pp" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 1 =>
                println(s"${vtep.get.physicalPort(UUID.fromString(args(0)))
                    .await(Timeout)}\n")
        }
    }
    commands += "create-ls" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 2 =>
                val logicalSwitch =
                    vtep.get
                        .ensureLogicalSwitch(args(0), Integer.valueOf(args(1)))
                        .await(Timeout)
                println(s"$logicalSwitch\n")
        }
    }
    commands += "delete-ls" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length == 1 =>
                vtep.get.removeLogicalSwitch(args(0)).await(Timeout)
                println(s"${vtep.get.logicalSwitch(args(0)).await(Timeout)}\n")
        }
    }
    commands += "bindings" -> new Command {
        override def run: Run = {
            case args if vtep.nonEmpty && args.length >= 1 =>
                val bindings = Seq(("port0", 1.toShort))
                val logicalSwitch =
                    vtep.get
                        .ensureBindings(args(0), bindings)
                        .await(Timeout)
                println(s"$logicalSwitch\n")
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
                        maxRetries: Long): Unit = {
        println(s"Connecting to VTEP $address:$port (retries every " +
                s"$retryInterval up to $maxRetries)\n")
        val client = OvsdbVtepDataClient(address, port, retryInterval,
                                         maxRetries)
        client.connect().await(Timeout)

        vtep = Some(client)
        stateSubscription = vtep.get.observable.subscribe(
            makeAction1 { state => println(s"VTEP state $state\n") },
            makeAction1 { e => println(s"VTEP error ${e.getMessage}") },
            makeAction0 { })

        println("Connect succeeded\n")
    }

    private def disconnect(): Unit = {
        vtep.get.close().await(Timeout)
        vtep = None

        stateSubscription.unsubscribe()
        stateSubscription = null
        println("Disconnect succeeded\n")
    }

}
