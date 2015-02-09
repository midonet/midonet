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
import org.midonet.cluster.cli.commands.objects.Network
import org.midonet.cluster.models.Topology.{Network => TopologyNetwork}

/**
 * Implements the NETWORK command.
 */
class NetworkCommand(cli: ClusterCli)
    extends StorageCommand[Network, TopologyNetwork](cli) {
    override val name = "network"

    override def run: Run = super.run orElse {
        case args if args.length >= 2 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length >= 2 && args(0).toLowerCase == "create" =>
            create(UUID.fromString(args(1)),
                   util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length == 4 && args(0).toLowerCase == "port" &&
                     args(1).toLowerCase == "add" =>
            val networkId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            addPort(networkId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "port" &&
                     args(1).toLowerCase == "delete" =>
            val networkId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            deletePort(networkId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "vxlan" &&
                     args(1).toLowerCase == "add" =>
            val networkId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            addVxlanPort(networkId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "vxlan" &&
                     args(1).toLowerCase == "delete" =>
            val networkId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            deleteVxlanPort(networkId, portId)
    }

    private def addPort(networkId: UUID, portId: UUID): CommandResult = {
        try {
            val network = storageGet(networkId)
            network.portIds += portId
            storageUpdate(network)
            println(s"Add port completed: $network")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deletePort(networkId: UUID, portId: UUID): CommandResult = {
        try {
            val network = storageGet(networkId)
            network.portIds -= portId
            storageUpdate(network)
            println(s"Delete port completed: $network")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def addVxlanPort(networkId: UUID, portId: UUID): CommandResult = {
        try {
            val network = storageGet(networkId)
            network.vxlanPortIds += portId
            storageUpdate(network)
            println(s"Add VXLAN port completed: $network")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteVxlanPort(networkId: UUID, portId: UUID): CommandResult = {
        try {
            val network = storageGet(networkId)
            network.vxlanPortIds -= portId
            storageUpdate(network)
            println(s"Delete VXLAN port completed: $network")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }
}
