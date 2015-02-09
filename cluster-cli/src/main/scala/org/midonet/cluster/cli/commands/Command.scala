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

import java.util.ResourceBundle

import org.midonet.cluster.cli.ClusterCli
import org.midonet.cluster.cli.ClusterCli.{HelpKey, message}
import org.midonet.cluster.cli.commands.Command.Run

object Command {
    type Run = PartialFunction[Array[String], CommandResult]
}

/**
 * The trait for a CLI command.
 */
trait Command {

    def name: String
    def run: Run = {
        case args if args.length == 1 && args(0) == "help" =>
            println(help)
            CommandSuccess
    }

    def help: String = message(name, HelpKey)

    final def description(obj: String, field: String): String = {
        ResourceBundle.getBundle(classOf[ClusterCli].getName)
            .getString(s"obj.$obj.$field")
    }
}

trait CommandResult
case object CommandSuccess extends CommandResult
case object CommandNone extends CommandResult
case object CommandExit extends CommandResult
case class CommandFailed(e: Throwable) extends CommandResult
case class CommandUnknown(command: String) extends CommandResult
case class CommandArgument(index: Int, arg: String, help: String)
    extends CommandResult
case class CommandSyntax(help: String) extends CommandResult
