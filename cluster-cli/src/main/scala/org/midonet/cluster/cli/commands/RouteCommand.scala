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
import org.midonet.cluster.cli.commands.objects.{RouterPort, Route}
import org.midonet.cluster.models.Topology.{Route => TopologyRoute, Port => TopologyPort}

/**
 * Implements the ROUTE command.
 */
class RouteCommand(cli: ClusterCli)
    extends StorageCommand[Route, TopologyRoute](cli) {
    override val name = "route"

    override def run: Run = super.run orElse {
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "router" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("router-id", args(3)) ++ array
            create(UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create"
                     && args(1).toLowerCase == "router" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("router-id", args(3)) ++ array
            create(UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "port" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("next-hop", "port", "next-hop-port-id", args(3),
                                  "next-hop-gateway", "255.255.255.255") ++ array
            create(UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create"
                     && args(1).toLowerCase == "port" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("next-hop", "port", "next-hop-port-id", args(3),
                                  "next-hop-gateway", "255.255.255.255") ++ array
            create(UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "local" &&
                     args(2).toLowerCase == "any" =>
            val portId = UUID.fromString(args(3))
            val port = storageGet(portId, classOf[RouterPort],
                                  classOf[TopologyPort])
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("next-hop", "local", "next-hop-port-id", args(3),
                                  "next-hop-gateway", "255.255.255.255",
                                  "src-network", "0.0.0.0/0",
                                  "dst-network",
                                  s"${port.portIp.toString}/32") ++ array
            create(UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create"
                     && args(1).toLowerCase == "local" =>
            val portId = UUID.fromString(args(3))
            val port = storageGet(portId, classOf[RouterPort],
                                  classOf[TopologyPort])
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("next-hop", "port", "next-hop-port-id", args(3),
                                  "next-hop-gateway", "255.255.255.255",
                                  "src-network", "0.0.0.0/0",
                                  "dst-network",
                                  s"${port.portIp.toString}/32") ++ array
            create(UUID.fromString(args(2)), seq.toArray)
    }
}
