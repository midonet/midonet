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
import org.midonet.cluster.cli.commands.objects._
import org.midonet.cluster.models.Topology.{Rule => TopologyRule}

/**
 * Implements the RULE command.
 */
class RuleCommand(cli: ClusterCli)
    extends StorageCommand[Rule, TopologyRule](cli) {
    override def name = "rule"

    override def run: Run = super.run orElse {
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "jump" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[JumpRule], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "jump" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[JumpRule], UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "literal" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[LiteralRule], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "literal" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[LiteralRule], UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "trace" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[TraceRule], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "trace" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[TraceRule], UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "fwd-nat" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[ForwardNatRule], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "fwd-nat" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[ForwardNatRule], UUID.fromString(args(2)), seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "rev-nat" &&
                     args(2).toLowerCase == "any" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[ReverseNatRule], UUID.randomUUID, seq.toArray)
        case args if args.length >= 4 && args(0).toLowerCase == "create" &&
                     args(1).toLowerCase == "rev-nat" =>
            val array = util.Arrays.copyOfRange(args, 4, args.length)
            val seq = Seq[String]("chain-id", args(3)) ++ array
            create(classOf[ReverseNatRule], UUID.fromString(args(2)), seq.toArray)
    }

    override def helpFields = super.helpFields +
        "\nFields for jump rule:\n" + fieldsAsString(classOf[JumpRule]) +
        "\nFields for NAT rule:\n" + fieldsAsString(classOf[NatRule]) +
        "\nFields for rule conditions:\n" + fieldsAsString(classOf[Condition])
}
