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

import org.midonet.cluster.cli.ClusterCli
import org.midonet.cluster.cli.commands.Command.Run
import org.midonet.cluster.cli.commands.objects.Host
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Host => TopologyHost}

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
        case args if args.length == 4 && args(0).toLowerCase == "tunnel-zone" &&
                     args(1).toLowerCase == "add" =>
            val hostId = UUID.fromString(args(2))
            val tzoneId = UUID.fromString(args(3))
            addTunnelZone(hostId, tzoneId)
        case args if args.length == 4 && args(0).toLowerCase == "tunnel-zone" &&
                     args(1).toLowerCase == "delete" =>
            val hostId = UUID.fromString(args(2))
            val tzoneId = UUID.fromString(args(3))
            deleteTunnelZone(hostId, tzoneId)
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
            case e: Throwable => CommandFailed(e)
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
            case e: Throwable => CommandFailed(e)
        }
    }

    private def addTunnelZone(hostId: UUID, tzoneId: UUID): CommandResult = {
        try {
            val host = storageGet(hostId)
            host.tunnelZoneIds += tzoneId
            storageUpdate(host)
            println(s"Add tunnel zone completed: $host")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteTunnelZone(hostId: UUID, tzoneId: UUID): CommandResult = {
        try {
            val host = storageGet(hostId)
            host.tunnelZoneIds -= tzoneId
            storageUpdate(host)
            println(s"Delete tunnel zone completed: $host")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }
}
