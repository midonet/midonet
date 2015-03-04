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
package org.midonet.midolman.management

import java.io.InputStreamReader
import java.lang.{Short => JShort, Integer => JInt, Byte => JByte}
import java.util.UUID

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConversions._

import com.typesafe.config._
import org.apache.curator.retry.RetryOneTime
import org.apache.curator.framework.CuratorFrameworkFactory
import org.midonet.conf.MidoNodeConfigurator
import org.midonet.config.HostIdGenerator
import org.rogach.scallop._

object ConfCommand {
    val SUCCESS = 0
    val FAILURE = 1

    implicit def scallopShortToBoxed(opt: ScallopOption[Short]): JShort =
        opt.get.map( new JShort(_) ).orNull

    implicit def scallopByteToBoxed(opt: ScallopOption[Byte]): JByte =
        opt.get.map( new JByte(_) ).orNull

    implicit def scallopIntToBoxed(opt: ScallopOption[Int]): JInt =
        opt.get.map( new JInt(_) ).orNull

    implicit def scallopStringToNaked(opt: ScallopOption[String]): String =
        opt.get.orNull

    implicit def scallopBooleanToNaked(opt: ScallopOption[Boolean]): Boolean =
        opt.get.getOrElse(false)

    implicit def scallopIntToNaked(opt: ScallopOption[Int]): Int =
        opt.get.orElse(Some(1)).get

}

trait ConfCommand {
    def run(configurator: MidoNodeConfigurator): Int
}

object SetTemplate extends TemplateCommand("template-set") with ConfCommand {
    descr("Modifies configuration template mappings")

    import ConfCommand._

    val template = opt[String]("template", short = 't', required = true, descr =
        "Config template to assign to the selected MidoNet host id")

    override def run(configurator: MidoNodeConfigurator) = {
        getHost map UUID.fromString  match  {
            case Some(h) =>
                configurator.assignTemplate(h, template)
                ConfCommand.SUCCESS
            case None =>
                throw new Exception("Could not parse or select host id to apply "+
                                    "config template on")
        }

    }
}

object GetTemplate extends TemplateCommand("template-get") with ConfCommand {
    descr("Queries configuration template mappings")

    val allHosts = opt[Boolean]("all", short = 'a', descr =
        "Query template assignments for all MidoNet hosts.")
    mutuallyExclusive(allHosts, hostId)

    override def run(configurator: MidoNodeConfigurator) = {
        val assignments = configurator.templateMappings
        val host = getHost

        if (allHosts.isDefined) {
            for (assignment <- assignments.entrySet;
                 key = assignment.getKey;
                 value = assignments.getString(key)) {
                println(s"$key  $value")
            }
            ConfCommand.SUCCESS
        } else host match {
            case Some(h) =>
                if (assignments.hasPath(h))
                    println(s"$h  ${assignments.getString(h)}")
                ConfCommand.SUCCESS
            case None =>
                ConfCommand.FAILURE
        }
    }
}

abstract class TemplateCommand(name: String) extends Subcommand(name) {
    val hostId = opt[String]("host", short = 'h', descr = "A MidoNet host id")

    def getHost: Option[String] = {
        if (hostId.isDefined)
            hostId.get
        else
            Option(HostIdGenerator.getIdFromPropertiesFile) map (_.toString)
    }
}

object BundledConfig extends Subcommand("dump-bundled-config") with ConfCommand {
    descr("Dumps bundled schemas and templates")

    val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).
                                                    setComments(true).
                                                    setJson(false).
                                                    setFormatted(true)

    override def run(configurator: MidoNodeConfigurator) = {
        val schema = configurator.bundledSchema.get
        val templates = configurator.bundledTemplates

        if (!schema.isEmpty) {
            println("Found a bundled schema:")
            print(schema.root().render(renderOpts))
            println()
        }

        for ((name, template) <- templates) {
            println(s"Found a bundled template [$name]:")
            print(template.get.root().render(renderOpts))
            println()
        }

        ConfCommand.SUCCESS
    }
}

object DeployBundledConfig extends Subcommand("deploy") with ConfCommand {
    descr("Deploys bundled schemas and templates")

    override def run(configurator: MidoNodeConfigurator) = {
        if (configurator.deployBundledConfig()) {
            val schema = configurator.bundledSchema
            val templates = configurator.bundledTemplates

            println(s"Deployed schema, version "+ schema.get.getString("schemaVersion"))
            for ((name, template) <- templates) {
                println(s"Deployed configuration template: $name ")
            }
        } else {
            println("Deployed schema is up to date, nothing to do")
        }
        ConfCommand.SUCCESS
    }
}

object SetConf extends ConfigWriter("set") with ConfCommand {
    descr("Accepts configuration from stdin and writes it to the selected configuration store.")

    val clear = opt[Boolean]("clear", short = 'c', default = Some(false), descr =
        "Clear existing configuration from the selected source before writing "+
        "the new configuration. The default behaviour is to merge the new "+
        "configuration on top of the existing one.")

    override def run(configurator: MidoNodeConfigurator) = {
        val schema = configurator.schema.get
        val zkConf = makeConfig(configurator)

        val reader = new InputStreamReader(System.in)
        val newConf = ConfigFactory.parseReader(reader)

        for (entry <- newConf.entrySet) {
            val key = entry.getKey
            val newVal = entry.getValue
            val schemaVal = try {
                schema.getValue(key)
            } catch {
                case e: ConfigException.Missing =>
                    throw new Exception(s"Missing schema value for $key")
            }

            if (!newVal.valueType().equals(schemaVal.valueType()))
                throw new Exception(s"Value for $key (${newVal.render()}}) does not follow schema type")
        }

        if (clear.get.get)
            zkConf.clearAndSet(newConf)
        else
            zkConf.mergeAndSet(newConf)
        ConfCommand.SUCCESS
    }
}

object UnsetConf extends ConfigWriter("unset") with ConfCommand {
    descr("Deletes a configuration option")

    import ConfCommand._

    val key = trailArg[String](required = true, descr = "Configuration key")

    override def run(configurator: MidoNodeConfigurator) = {
        val zkConf = makeConfig(configurator)

        if (zkConf.get.hasPath(key))
            zkConf.unset(key)

        ConfCommand.SUCCESS
    }
}

abstract class ConfigWriter(name: String) extends Subcommand(name) {
    val hostId = opt[String]("host", short = 'h', descr =
        "Modify per-host configuration for the host with the given host id. "+
        "'local' will resolve to the local host id.")
    val template = opt[String]("template", short = 't', descr =
        "Modifies configuration values in this template.")
    mutuallyExclusive(hostId, template)

    def makeConfig(configurator: MidoNodeConfigurator) = {
        implicit def cfgOptToNaked(opt: Option[Config]): Config = opt.getOrElse(ConfigFactory.empty)

        val hostOpt: Option[UUID] = hostId.get flatMap { h =>
            Option(if (h == "local") HostIdGenerator.getIdFromPropertiesFile else UUID.fromString(h))
        }
        val templateNameOpt: Option[String] = template.get

        if (hostOpt.isDefined) {
            configurator.centralPerNodeConfig(hostOpt.get)
        } else if (templateNameOpt.isDefined) {
            configurator.templateByName(templateNameOpt.get)
        } else {
            throw new Exception("Couldn't choose a configuration source to " +
                "write to, please indicate it by providing the template or host "+
                "name you want to edit.")
        }

    }
}

object DumpConf extends ConfigQuery("dump") with ConfCommand {
    descr("Prints out a dump of the configuration")

    override def run(configurator: MidoNodeConfigurator) = {
        print(makeConfig(configurator).root().render(renderOpts))
        println()
        ConfCommand.SUCCESS
    }
}

object GetConf extends ConfigQuery("get") with ConfCommand {
    descr("Query configuration for a specific value")

    val key = trailArg[String](required = true, descr = "Configuration key")

    override def run(configurator: MidoNodeConfigurator) = {
        val conf = makeConfig(configurator)

        try {
            if (schema.get.get) {
                val s = conf.getString(s"${key.get.get}.description")
                println(s"$s")
            }

            val v = conf.getValue(key.get.get).render(renderOpts)
            println(s"${key.get.get} = $v")
        } catch {
            case e: ConfigException.Missing =>
                if (schema.get.get)
                    throw new Exception(s"Schema not found for ${key.get.get}")
        }

        ConfCommand.SUCCESS
    }
}

abstract class ConfigQuery(name: String) extends Subcommand(name) {
    import ConfCommand._

    val renderOpts = ConfigRenderOptions.defaults().setOriginComments(true).
                                                    setComments(false).
                                                    setJson(false).
                                                    setFormatted(true)

    val hostId = opt[String]("host", short = 'h', descr =
        "Queries configuration for a particular MidoNet node, defaults to the " +
        "midonet host id of the local host. If the default is overridden by this "+
        "option, local config-file based sources (which are deprecated) will be "+
        "ignored. If no host id is found via either means, mn-config will query "+
        "the default configuration template")
    val template = opt[String]("template", short = 't', descr =
        "Queries a configuration template. If this option is given all "+
        "host-specific configuration will be ignored")
    val hostOnly = opt[Boolean](
        "host-only", short = 'H', default = Some(false), descr =
        "Queries exclusively the per-host configuration values. Schema values "+
        "(defaults) and template-based values are ignored. The host id can be "+
        "given (-h) or looked for on the local host.")
    val schema = opt[Boolean](
        "schema", short = 's', default = Some(false), descr =
        "Do not query any configuration sources but the schema, including "+
        "key documentation.")

    mutuallyExclusive(template, hostOnly)
    mutuallyExclusive(template, schema)
    mutuallyExclusive(hostOnly, schema)

    def makeConfig(configurator: MidoNodeConfigurator) = {
        implicit def cfgOptToNaked(opt: Option[Config]): Config = opt.getOrElse(ConfigFactory.empty)

        val hostOpt: Option[UUID] = hostId.get map UUID.fromString orElse Option(HostIdGenerator.getIdFromPropertiesFile)
        var conf = ConfigFactory.empty

        val templateOnly: Boolean = template.get.isDefined
        val useTemplate: Boolean = templateOnly || (!schema && !hostOnly)

        val useLocalConf: Boolean = hostId.get.isEmpty && (!templateOnly && !schema)
        val usePerHostConf: Boolean = hostOpt.isDefined && (!templateOnly && !schema)

        val useSchema: Boolean = !hostOnly && !templateOnly

        val templateName: String = {
            template.get.orElse(hostOpt map configurator.templateNameForNode)
        }.getOrElse("default")

        if (useLocalConf)
            conf = conf.withFallback(configurator.localOnlyConfig)

        if (usePerHostConf)
            conf = conf.withFallback(hostOpt map (configurator.centralPerNodeConfig(_).get))

        if (useTemplate) {
            conf = conf.withFallback(configurator.templateByName(templateName).get)
            if (templateName != "default")
                conf = conf.withFallback(configurator.templateByName("default").get)
        }

        if (useSchema)
            conf = conf.withFallback(configurator.schema.get)

        conf
    }
}

object MidolmanConf extends App {
    def getConfigurator(host: String, port: Int) = {
        val zk = CuratorFrameworkFactory.newClient(s"$host:$port", new RetryOneTime(1000))
        zk.start()
        MidoNodeConfigurator.forAgents(zk)
    }
    def date = System.currentTimeMillis()
    val opts = new ScallopConf(args) {
        val port = opt[Int]("port", short = 'p', default = Option(2181),
                            descr = "ZooKeeper port",
                            required = true)
        val host = opt[String]("zookeeper", short = 'z', default = Option("localhost"),
                               descr = "ZooKeeper Host")

        val bundledConfig = BundledConfig
        val dump = DumpConf
        val deploy = DeployBundledConfig
        val get = GetConf
        val set = SetConf
        val unset = UnsetConf
        val templateGet = GetTemplate
        val templateSet = SetTemplate

        printedName = "mm-conf"
        footer("Copyright (c) 2015 Midokura SARL, All Rights Reserved.")
    }

    val ret = (opts.subcommand flatMap {
        case subcommand: ConfCommand =>
            for {host <- opts.host.get
                 port <- opts.port.get} yield { (subcommand, host, port) }
        case s =>
            println(s)
            None
    } match {
        case Some((subcommand, host, port)) =>
            Try(subcommand.run(getConfigurator(host, port)))
        case _ =>
            Failure(new Exception("must specify a valid command"))
    }) match {
        case Success(retcode) =>
            retcode
        case Failure(e) =>
            System.err.println("[mm-conf] Failed: " + e.getMessage)
            throw e
            1
    }

    System.exit(ret)
}
