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
import org.midonet.cluster.cli.commands.Command._
import org.midonet.cluster.cli.commands.objects.Chain
import org.midonet.cluster.models.Topology.{Chain => TopologyChain}

/**
 * Implements the CHAIN command.
 */
class ChainCommand(cli: ClusterCli)
    extends StorageCommand[Chain, TopologyChain](cli) {
    override val name = "chain"

    override def run: Run = super.run orElse {
        case args if args.length >= 2 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "any" =>
            create(UUID.randomUUID, util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length >= 2 && args(0).toLowerCase == "create" =>
            create(UUID.fromString(args(1)),
                   util.Arrays.copyOfRange(args, 2, args.length))
        case args if args.length == 4 && args(0).toLowerCase == "rule" &&
                     args(1).toLowerCase == "add" =>
            val chainId = UUID.fromString(args(2))
            val ruleId = UUID.fromString(args(3))
            addRule(chainId, ruleId)
        case args if args.length == 4 && args(0).toLowerCase == "rule" &&
                     args(1).toLowerCase == "delete" =>
            val chainId = UUID.fromString(args(2))
            val ruleId = UUID.fromString(args(3))
            deleteRule(chainId, ruleId)
        case args if args.length == 4 && args(0).toLowerCase == "network" &&
                     args(1).toLowerCase == "add" =>
            val chainId = UUID.fromString(args(2))
            val networkId = UUID.fromString(args(3))
            addNetwork(chainId, networkId)
        case args if args.length == 4 && args(0).toLowerCase == "network" &&
                     args(1).toLowerCase == "delete" =>
            val chainId = UUID.fromString(args(2))
            val networkId = UUID.fromString(args(3))
            deleteNetwork(chainId, networkId)
        case args if args.length == 4 && args(0).toLowerCase == "router" &&
                     args(1).toLowerCase == "add" =>
            val chainId = UUID.fromString(args(2))
            val routerId = UUID.fromString(args(3))
            addRouter(chainId, routerId)
        case args if args.length == 4 && args(0).toLowerCase == "router" &&
                     args(1).toLowerCase == "delete" =>
            val chainId = UUID.fromString(args(2))
            val routerId = UUID.fromString(args(3))
            deleteRouter(chainId, routerId)
        case args if args.length == 4 && args(0).toLowerCase == "port" &&
                     args(1).toLowerCase == "add" =>
            val chainId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            addPort(chainId, portId)
        case args if args.length == 4 && args(0).toLowerCase == "port" &&
                     args(1).toLowerCase == "delete" =>
            val chainId = UUID.fromString(args(2))
            val portId = UUID.fromString(args(3))
            deletePort(chainId, portId)
    }

    private def addRule(chainId: UUID, ruleId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.ruleIds += ruleId
            storageUpdate(chain)
            println(s"Add rule completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteRule(chainId: UUID, ruleId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.ruleIds -= ruleId
            storageUpdate(chain)
            println(s"Delete rule completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def addNetwork(chainId: UUID, networkId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.networkIds += networkId
            storageUpdate(chain)
            println(s"Add network completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteNetwork(chainId: UUID, networkId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.networkIds -= networkId
            storageUpdate(chain)
            println(s"Delete network completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def addRouter(chainId: UUID, routerId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.routerIds += routerId
            storageUpdate(chain)
            println(s"Add router completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deleteRouter(chainId: UUID, routerId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.routerIds -= routerId
            storageUpdate(chain)
            println(s"Delete router completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def addPort(chainId: UUID, portId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.portIds += portId
            storageUpdate(chain)
            println(s"Add port completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    private def deletePort(chainId: UUID, portId: UUID): CommandResult = {
        try {
            val chain = storageGet(chainId)
            chain.portIds -= portId
            storageUpdate(chain)
            println(s"Delete port completed: $chain")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }
}
