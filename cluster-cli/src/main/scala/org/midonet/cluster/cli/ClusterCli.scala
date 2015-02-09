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

package org.midonet.cluster.cli

import java.util
import java.util.ResourceBundle
import java.util.concurrent.Executors

import scala.io.StdIn

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.retry.RetryOneTime

import org.midonet.cluster.cli.commands._
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction.{CLEAR, ERROR}
import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.models.Topology.{TunnelZone, Port, Host, Network}
import org.midonet.config.ConfigProvider

/**
 * Contains the main method of the cluster CLI.
 */
object ClusterCli {

    final val CliTopic = "cli"
    final val CommandTopic = "command"

    final val TitleKey = "title"
    final val ConnectKey = "connect"
    final val HelpKey = "help"
    final val ConfigKey = "config"
    final val CuratorKey = "curator"
    final val FailedKey = "failed"
    final val ErrorKey = "error"
    final val UnknownKey = "unknown"
    final val ArgumentKey = "argument"
    final val SyntaxKey = "syntax"

    def main(args: Array[String]): Unit = {
        println(message(CliTopic, TitleKey))

        // Create a CLI instance.
        try {
            val cli = new ClusterCli(args)
            cli.start()
            var index = 0
            do {
                val text = StdIn.readLine(s"${cli.config.prompt} ($index)> ")
                try {
                    cli.run(text) match {
                        case CommandNone =>
                        case CommandSuccess => index = index + 1
                        case CommandExit =>
                            cli.close()
                            System.exit(0)
                        case CommandFailed(e) =>
                            println(message(CommandTopic, FailedKey,
                                            e.getMessage))
                        case CommandUnknown(command) =>
                            println(message(CommandTopic, UnknownKey, command))
                        case CommandArgument(idx, arg, help) =>
                            println(message(CommandTopic, ArgumentKey,
                                            Int.box(idx), arg))
                            println(help)
                        case CommandSyntax(help) =>
                            println(message(CommandTopic, SyntaxKey))
                            println(help)
                        case _ =>
                            println(message(CommandTopic, UnknownKey, text))
                    }
                } catch {
                    case e: Throwable =>
                        println(message(CommandTopic, ErrorKey, text,
                                        e.getMessage))
                }
            }
            while (true)
        } catch {
            case e: IllegalArgumentException =>
                println(message(CliTopic, HelpKey))
            case e: RuntimeException =>
                println(message(CliTopic, ConfigKey, e.getMessage))
            case e: Exception =>
                println(message(CliTopic, ErrorKey, e.getMessage))
        }
    }

    protected[cli] def message(topic: String, key: String, args: AnyRef*)
    : String = {
        String.format(ResourceBundle.getBundle(classOf[ClusterCli].getName)
            .getString(s"$topic.$key"), args:_*)
    }

}

/**
 * Creates a cluster CLI instance with the given argument list.
 */
class ClusterCli(args: Array[String]) {

    import ClusterCli._

    private val commandSeparators = Array(' ', '\t', '\n')
    private val configProvider = createConfigProvider

    protected[cli] val config =
        configProvider.getConfig(classOf[ClusterCliConfig])
    protected[cli] val executor = Executors.newCachedThreadPool()
    protected[cli] val curator =
        CuratorFrameworkFactory.newClient(config.hosts, new RetryOneTime(1000))
    protected[cli] val storage =
        new ZookeeperObjectMapper(config.zoomPath, curator)

    private val commands = Map[String, Command](
        "exit" -> new ExitCommand,
        "help" -> new HelpCommand,
        "host" -> new HostCommand(this),
        "port" -> new PortCommand(this),
        "network" -> new NetworkCommand(this),
        "tunnel-zone" -> new TunnelZoneCommand(this)
    )

    /** Starts the underlying storage. */
    def start(): Unit = {
        println(message(CliTopic, ConnectKey, config.hosts))
        curator.start()
        if (curator.getState == CuratorFrameworkState.STOPPED) {
            message(CliTopic, CuratorKey, config.hosts)
            System.exit(-1)
        }
        storage.registerClass(classOf[Host])
        storage.registerClass(classOf[Port])
        storage.registerClass(classOf[Network])
        storage.registerClass(classOf[TunnelZone])
        storage.declareBinding(classOf[Network], "port_ids", ERROR,
                               classOf[Port], "network_id", CLEAR)
        storage.build()
    }

    /** Closes the underlying storage. */
    def close(): Unit = {
        curator.close()
    }

    /** Runs the given command text. */
    def run(text: String): CommandResult = {
        runCommand(text.split(commandSeparators).filter(_.nonEmpty))
    }

    /** Creates a configuration provider for the current arguments. */
    @throws[IllegalArgumentException]
    @throws[RuntimeException]
    private def createConfigProvider: ConfigProvider = {
        // Validate the arguments.
        if ((args eq null) || (args.length != 1)) {
            throw new IllegalArgumentException
        }
        ConfigProvider.fromIniFile(args(0))
    }

    /** Runs a command. */
    private def runCommand(args: Array[String]): CommandResult = {
        if (0 == args.length) return CommandNone
        val command = commands.getOrElse(args(0).toLowerCase,
                                         return CommandUnknown(args(0)))
        command.run.applyOrElse(
            util.Arrays.copyOfRange(args, 1, args.length),
            (_: Array[String]) => CommandSyntax(command.help))
    }

}