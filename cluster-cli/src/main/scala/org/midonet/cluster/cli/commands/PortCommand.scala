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

package org.midonet.cluster.cli.commands

import java.util
import java.util.UUID

import org.midonet.cluster.cli.ClusterCli
import org.midonet.cluster.cli.commands.Command.Run
import org.midonet.cluster.cli.commands.objects.{VxLanPort, RouterPort, NetworkPort, Port}
import org.midonet.cluster.models.Topology.{Port => TopologyPort}

/**
 * Implements the PORT command.
 */
class PortCommand(cli: ClusterCli)
    extends StorageCommand[Port, TopologyPort](cli) {
    override val name = "port"

    override def run: Run = super.run orElse {
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "network" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("network-id", args(3)) ++ array
            create(classOf[NetworkPort], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "network" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("network-id", args(3)) ++ array
            create(classOf[NetworkPort], UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "router" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("router-id", args(3)) ++ array
            create(classOf[RouterPort], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "router" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("router-id", args(3)) ++ array
            create(classOf[RouterPort], UUID.fromString(args(2)), seq.toArray)
        case args if args.length == 4 && args(0).toLowerCase == "route" &&
                     args(1).toLowerCase == "add" =>
            val portId = UUID.fromString(args(2))
            val routeId = UUID.fromString(args(3))
            addRoute(portId, routeId)
        case args if args.length == 4 && args(0).toLowerCase == "route" &&
                     args(1).toLowerCase == "delete" =>
            val portId = UUID.fromString(args(2))
            val routeId = UUID.fromString(args(3))
            deleteRoute(portId, routeId)
    }

    private def addRoute(portId: UUID, routeId: UUID): CommandResult = {
        try {
            val port = storageGet(portId).asInstanceOf[RouterPort]
            port.routeIds += routeId
            storageUpdate(port)
            println(s"Add route completed: $port")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteRoute(portId: UUID, routeId: UUID): CommandResult = {
        try {
            val port = storageGet(portId).asInstanceOf[RouterPort]
            port.routeIds += routeId
            storageUpdate(port)
            println(s"Delete route completed: $port")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    override def helpFields = super.helpFields +
        "\nFields for network port:\n" + fieldsAsString(classOf[NetworkPort]) +
        "\nFields for router port:\n" + fieldsAsString(classOf[RouterPort]) +
        "\nFields for VXLAN port:\n" + fieldsAsString(classOf[VxLanPort])
}
