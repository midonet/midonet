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
import org.midonet.cluster.cli.commands.objects.{TunnelZoneType, TunnelZone}
import org.midonet.cluster.models.Topology.{TunnelZone => TopologyTunnelZone}
import org.midonet.packets.IPAddr

/**
 * Implements the TUNNEL-ZONE command.
 */
class TunnelZoneCommand(cli: ClusterCli)
    extends StorageCommand[TunnelZone, TopologyTunnelZone](cli) {
    override val name = "tunnel-zone"

    override def run: Run = super.run orElse {
        case args if args.length >= 3 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "gre" && args(2).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 3, args.length),
                   _.zoneType = TunnelZoneType.GRE)
        case args if args.length >= 3 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "vxlan" && args(2).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 3, args.length),
                   _.zoneType = TunnelZoneType.VXLAN)
        case args if args.length >= 3 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "vtep" && args(2).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 3, args.length),
                   _.zoneType = TunnelZoneType.VTEP)
        case args if args.length >= 3 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "gre" =>
            create(UUID.fromString(args(2)),
                   util.Arrays.copyOfRange(args, 3, args.length),
                   _.zoneType = TunnelZoneType.GRE)
        case args if args.length >= 3 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "vxlan" =>
            create(UUID.fromString(args(2)),
                   util.Arrays.copyOfRange(args, 3, args.length),
                   _.zoneType = TunnelZoneType.VXLAN)
        case args if args.length >= 3 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "vtep" =>
            create(UUID.fromString(args(2)),
                   util.Arrays.copyOfRange(args, 3, args.length),
                   _.zoneType = TunnelZoneType.VTEP)
        case args if args.length == 5 && args(0).toLowerCase == "host" &&
                     args(1).toLowerCase == "add" =>
            val tunnelZoneId = UUID.fromString(args(2))
            val hostId = UUID.fromString(args(3))
            val ipAddr = IPAddr.fromString(args(4))
            addHost(tunnelZoneId, hostId, ipAddr)
        case args if args.length == 4 && args(0).toLowerCase == "host" &&
                     args(1).toLowerCase == "delete" =>
            val tunnelZoneId = UUID.fromString(args(2))
            val hostId = UUID.fromString(args(3))
            deleteHost(tunnelZoneId, hostId)
    }

    private def addHost(tunnelZoneId: UUID, hostId: UUID, ipAddr: IPAddr)
    : CommandResult = {
        try {
            val tunnelZone = storageGet(tunnelZoneId)
            tunnelZone.hosts += hostId -> ipAddr
            storageUpdate(tunnelZone)
            println(s"Add host completed: $tunnelZone")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteHost(tunnelZoneId: UUID, hostId: UUID): CommandResult = {
        try {
            val tunnelZone = storageGet(tunnelZoneId)
            tunnelZone.hosts -= hostId
            storageUpdate(tunnelZone)
            println(s"Delete host completed: $tunnelZone")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

}
