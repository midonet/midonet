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

import scala.concurrent.Await
import scala.util.control.NonFatal

import org.midonet.cluster.cli.ClusterCli
import org.midonet.cluster.cli.commands.Command.Run
import org.midonet.cluster.cli.commands.objects.{Interface, TunnelZone, Host}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.{Host => TopologyHost}
import org.midonet.packets.IPAddr

/**
 * Implements the HOST command.
 */
class HostCommand(cli: ClusterCli)
    extends StorageCommand[Host, TopologyHost](cli) {
    override val name = "host"

    override def run: Run = super.run orElse {
        case args if args.length >= 2 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length >= 2 && args(0).toLowerCase == "create" =>
            create(UUID.fromString(args(1)),
                   util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length == 5 && args(0).toLowerCase == "binding" &&
                     args(1).toLowerCase == "add" =>
            val hostId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            val iface = args(4)
            addBinding(hostId, portId, iface)
        case args if args.length == 4 && args(0).toLowerCase == "binding" &&
                     args(1).toLowerCase == "delete" =>
            val hostId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            deleteBinding(hostId, portId)
        case args if args.length >= 4 && args(0).toLowerCase == "interface" &&
                     args(1).toLowerCase == "add" =>
            val hostId = UUID.fromString(args(2))
            val interfaceName = args(3)
            addInterface(hostId, interfaceName,
                         util.Arrays.copyOfRange(args, 4, args.length))
        case args if args.length == 4 && args(0).toLowerCase == "interface" &&
                     args(1).toLowerCase == "delete" =>
            val hostId = UUID.fromString(args(2))
            val interfaceName = args(3)
            deleteInterface(hostId, interfaceName)
        case args if args.length == 6 && args(0).toLowerCase == "interface" &&
                     args(1).toLowerCase == "address" &&
                     args(2).toLowerCase == "add" =>
            val hostId = UUID.fromString(args(3))
            val interfaceName = args(4)
            val ipAddr = IPAddr.fromString(args(5))
            addInterfaceAddress(hostId, interfaceName, ipAddr)
        case args if args.length == 6 && args(0).toLowerCase == "interface" &&
                     args(1).toLowerCase == "address" &&
                     args(2).toLowerCase == "delete" =>
            val hostId = UUID.fromString(args(3))
            val interfaceName = args(4)
            val ipAddr = IPAddr.fromString(args(5))
            deleteInterfaceAddress(hostId, interfaceName, ipAddr)
        case args if args.length == 3 && args(0) == "state" &&
                     args(2) == "on" =>
            val hostId = UUID.fromString(args(1))
            stateOn(hostId)
        case args if args.length == 3 && args(0) == "state" &&
                     args(2) == "off" =>
            val hostId = UUID.fromString(args(1))
            stateOff(hostId)
    }

    private def addBinding(hostId: UUID, portId: UUID, iface: String)
    : CommandResult = {
        try {
            val host = storageGet(hostId)
            host.portBindings += portId -> iface
            storageUpdate(host)
            println(s"Add binding completed: $host")
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def deleteBinding(hostId: UUID, portId: UUID): CommandResult = {
        try {
            val host = storageGet(hostId)
            host.portBindings -= portId
            storageUpdate(host)
            println(s"Delete binding completed: $host")
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def addInterface(hostId: UUID, interfaceName: String,
                             args: Array[String]): CommandResult = {
        try {
            val host = storageGet(hostId)
            val interface = newObject(classOf[Interface], args)
            interface.name = interfaceName
            host.interfaces += interface
            storageUpdate(host)
            println(s"Add host interface completed: $host")
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def deleteInterface(hostId: UUID, interfaceName: String)
    : CommandResult = {
        try {
            val host = storageGet(hostId)
            host.interfaces = host.interfaces.filterNot(_.name == interfaceName)
            storageUpdate(host)
            println(s"Delete host interface completed: $host")
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def addInterfaceAddress(hostId: UUID, interfaceName: String,
                                    ipAddr: IPAddr): CommandResult = {
        try {
            val host = storageGet(hostId)
            host.interfaces.filter(_.name == interfaceName)
                           .foreach(_.addresses += ipAddr)
            storageUpdate(host)
            println(s"Add host interface address completed: $host")
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def deleteInterfaceAddress(hostId: UUID, interfaceName: String,
                                       ipAddr: IPAddr): CommandResult = {
        try {
            val host = storageGet(hostId)
            host.interfaces.filter(_.name == interfaceName)
                .foreach(_.addresses -= ipAddr)
            storageUpdate(host)
            println(s"Delete host interface address completed: $host")
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def stateOn(hostId: UUID): CommandResult = {
        try {
            storageStateUpdate(hostId, hostId.toString)
            state(hostId.toString)
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    private def stateOff(hostId: UUID): CommandResult = {
        try {
            storageStateDelete(hostId, hostId.toString)
            state(hostId.toString)
            CommandSuccess
        } catch {
            case NonFatal(e) => CommandFailed(e)
        }
    }

    override def helpFields = super.helpFields +
        "\nFields for host interface:\n" + fieldsAsString(classOf[Interface])
}
