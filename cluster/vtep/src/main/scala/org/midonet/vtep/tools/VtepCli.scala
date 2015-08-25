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

package org.midonet.vtep.tools

import java.util

import scala.concurrent.duration._
import scala.collection.mutable
import scala.io.StdIn
import scala.util.control.NonFatal

import rx.Subscription

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState._
import org.midonet.packets.IPv4Addr
import org.midonet.vtep.OvsdbVtepDataClient
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction0, makeAction1}

object VtepCli extends App {

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

    commands += "exit" -> new Command {
        override def run: Run = { case _ => System exit 0 }
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
            case _ if vtep.isEmpty =>
                throw Error("VTEP not connected")
            case _ =>
                println(s"Disconnecting from VTEP ${vtep.get.endPoint}\n")
                vtep.get.disconnect()
                vtep.get.awaitState(Set(Disconnected, Failed), Timeout)
                vtep = None
                println("Disconnect succeeded\n")
        }
    }
    commands += "state" -> new Command {
        override def run: Run = {
            case args if args.length == 1 && args(0).toLowerCase == "on" =>
                if (vtep.isEmpty)
                    throw Error("Not connected to VTEP")
                if (stateSubscription ne null)
                    throw Error("State monitoring is already on")
                stateSubscription = vtep.get.observable.subscribe(
                    makeAction1 { state => println(s"VTEP state $state\n") },
                    makeAction1 { e => println(s"VTEP error ${e.getMessage}") },
                    makeAction0 { })
            case args if args.length == 1 && args(0).toLowerCase == "on" =>
                if (stateSubscription eq null)
                    throw Error("State monitoring is off")
                stateSubscription.unsubscribe()
                stateSubscription = null
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
        println("Connect succeeded\n")
    }

}
