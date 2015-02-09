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
import org.midonet.cluster.cli.commands.objects.Router
import org.midonet.cluster.models.Topology.{Router => TopologyRouter}

/**
 * Implements the ROUTER command.
 */
class RouterCommand(cli: ClusterCli)
    extends StorageCommand[Router, TopologyRouter](cli) {
    override val name = "router"

    override def run: Run = super.run orElse {
        case args if args.length >= 2 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length >= 2 && args(0).toLowerCase == "create" =>
            create(UUID.fromString(args(1)),
                   util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length == 4 && args(0).toLowerCase == "port" &&
                     args(1).toLowerCase == "add" =>
            val routerId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            addPort(routerId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "port" &&
                     args(1).toLowerCase == "delete" =>
            val routerId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            deletePort(routerId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "route" &&
                     args(1).toLowerCase == "add" =>
            val routerId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            addRoute(routerId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "route" &&
                     args(1).toLowerCase == "delete" =>
            val routerId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            deleteRoute(routerId, portId)
    }

    private def addPort(routerId: UUID, portId: UUID): CommandResult = {
        try {
            val router = storageGet(routerId)
            router.portIds = router.portIds + portId
            storageUpdate(router)
            println(s"Add port completed: $router")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deletePort(routerId: UUID, portId: UUID): CommandResult = {
        try {
            val router = storageGet(routerId)
            router.portIds = router.portIds - portId
            storageUpdate(router)
            println(s"Delete port completed: $router")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def addRoute(routerId: UUID, routeId: UUID): CommandResult = {
        try {
            val router = storageGet(routerId)
            router.routeIds += routeId
            storageUpdate(router)
            println(s"Add route completed: $router")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteRoute(routerId: UUID, routeId: UUID): CommandResult = {
        try {
            val router = storageGet(routerId)
            router.routeIds -= routeId
            storageUpdate(router)
            println(s"Delete route completed: $router")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }
}
