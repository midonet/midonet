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
package org.midonet.conf

import java.io.InputStreamReader
import java.lang.{Short => JShort, Integer => JInt, Byte => JByte}
import java.util.UUID
import org.apache.zookeeper.KeeperException

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConversions._

import com.typesafe.config._
import org.rogach.scallop._

import org.midonet.conf.HostIdGenerator.PropertiesFileNotWritableException

object ConfCommand {
    val SUCCESS = 0
    val FAILURE = 1
    val NO_SUCH_KEY = 11

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

object ListHosts extends Subcommand("hosts") with ConfCommand {
    descr("List hosts information")

    import ConfCommand._

    override def run(configurator: MidoNodeConfigurator) = {
        val mappings = configurator.templateMappings
        val mappingsMap = mappings.entrySet() map { e => e.getKey -> e.getValue.unwrapped().asInstanceOf[String] } toMap
        val perHost = configurator.listPerNodeConfigs.toSet

        val defaults = "template(default) : schema"
        for (host <- perHost) {
            if (mappingsMap.contains(host) && (mappingsMap(host) != "default")) {
                val template = mappingsMap(host)
                println(s"$host -> custom : template($template) : $defaults")
            } else {
                println(s"$host -> custom : $defaults")
            }
        }

        for ((host, template) <- mappingsMap) {
            if (!perHost.contains(host)) {
                println(s"$host -> template($template) : $defaults")
            }
        }

        ConfCommand.SUCCESS
    }
}

object ListTemplate extends Subcommand("template-list") with ConfCommand {
    descr("List configuration templates")

    import ConfCommand._

    override def run(configurator: MidoNodeConfigurator) = {
        val mappings = configurator.templateMappings
        val templates = configurator.listTemplates

        val mappingsMap = mappings.entrySet() map { e => e.getKey -> e.getValue.unwrapped().asInstanceOf[String] } toMap
        val inverseMappings = mappingsMap.groupBy(_._2).mapValues(_.map(_._1))

        for (template <- templates) {
            println(template)
            for (host <- inverseMappings.getOrElse(template, Seq.empty)) {
                println(s"    $host")
            }
        }
        ConfCommand.SUCCESS
    }
}

object ClearTemplate extends TemplateCommand("template-clear") with ConfCommand {
    descr("Clears configuration template mappings")

    import ConfCommand._

    override def run(configurator: MidoNodeConfigurator) = {
        getHost map UUID.fromString  match  {
            case Some(h) =>
                configurator.clearTemplate(h)
                ConfCommand.SUCCESS
            case None =>
                throw new Exception("Could not parse or select host id to clear "+
                    "config template from")
        }

    }
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

        if (allHosts.isDefined && allHosts.get.get) {
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
    val hostId = opt[String]("host", short = 'h',
                    descr = "A MidoNet host id, defaults to the local host id.")

    def getHost: Option[String] = {
        if (hostId.isDefined && (hostId.get.get != "local"))
            hostId.get
        else
            Option(HostIdGenerator.getHostId()) map (_.toString)
    }
}

object BundledConfig extends Subcommand("dump-bundled-config") with ConfCommand {
    descr("Dumps bundled schemas and templates")

    val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).
                                                    setComments(true).
                                                    setJson(false).
                                                    setFormatted(true)

    override def run(configurator: MidoNodeConfigurator) = {
        val schema = configurator.mergedBundledSchemas
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
            val templates = configurator.bundledTemplates
            println(s"Deployed schema")
            for ((name, template) <- templates) {
                println(s"Deployed configuration template: $name ")
            }
        } else {
            println("Deployed schema is up to date, nothing to do")
        }
        ConfCommand.SUCCESS
    }
}

object ImportConf extends ConfigWriter("import") with ConfCommand {
    descr("Imports a legacy configuration file into the selected configuration source.")

    val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).
                                                    setComments(false).
                                                    setJson(false).
                                                    setFormatted(true)

    val filename = opt[String]("file", short = 'f', required = true, descr =
        "Path to the file to be imported. ")
    val importAll = opt[Boolean]("all", short = 'a', default = Some(false), descr =
        "Import all the values present in the config file even if they do not "+
            "differ from the configuration schema.")

    override def run(configurator: MidoNodeConfigurator) = {
        val newConf = new LegacyConf(filename.get.get).get
        val dest = makeConfig(configurator)

        var toImport = newConf

        if (!importAll.get.get) {
            val schemas = configurator.mergedSchemas().resolve()
            toImport = ConfigFactory.empty

            for (entry <- newConf.entrySet) {
                val k = entry.getKey
                if (!schemas.hasPath(k) || schemas.getString(k) != newConf.getString(k)) {
                    toImport = toImport.withValue(k, entry.getValue)
                }
            }
        }

        println("Importing legacy configuration:")
        println(toImport.root().render(renderOpts))

        dest.mergeAndSet(toImport)
        ConfCommand.SUCCESS
    }
}

object SetConf extends ConfigWriter("set") with ConfCommand {
    descr("Accepts configuration from stdin or trailing arguments and writes it to the selected configuration store.")

    val trailing = trailArg[String](descr = "Configuration to be written, if not given, read from STDIN",
                                    required = false)

    val clear = opt[Boolean]("clear", short = 'c', default = Some(false), descr =
        "Clear existing configuration from the selected source before writing "+
        "the new configuration. The default behaviour is to merge the new "+
        "configuration on top of the existing one.")

    override def run(configurator: MidoNodeConfigurator) = {
        val parseOpts = ConfigParseOptions.defaults().setOriginDescription("STDIN")
        val newConf: Config =
            if (trailing.isDefined && trailing.get.isDefined) {
                val args = trailing.get.get
                val confText = args.mkString(" ")
                ConfigFactory.parseString(confText)
            } else {
                ConfigFactory.parseReader(new InputStreamReader(System.in), parseOpts)
            }

        configurator.validate(newConf) foreach { k =>
            println(s"Installing config key that was not found in the schema: $k")
        }

        val zkConf = makeConfig(configurator)
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
        "'local' will resolve to the local host id.", default = Some("local"))
    val template = opt[String]("template", short = 't', descr =
        "Modifies configuration values in this template.")
    mutuallyExclusive(hostId, template)

    def makeConfig(configurator: MidoNodeConfigurator) = {
        implicit def cfgOptToNaked(opt: Option[Config]): Config = opt.getOrElse(ConfigFactory.empty)

        val templateNameOpt: Option[String] = template.get

        if (templateNameOpt.isDefined) {
            configurator.templateByName(templateNameOpt.get)
        } else {
            hostId.get map {
                case "local" => HostIdGenerator.getHostId()
                case id => UUID.fromString(id)
            } match {
                case Some(h) =>
                    configurator.centralPerNodeConfig(h)
                case None =>
                    throw new Exception("Couldn't choose a configuration source to " +
                        "write to, please indicate it by providing the template or host "+
                        "name you want to edit.")
            }
        }

    }
}

object DumpConf extends ConfigQuery("dump") with ConfCommand {
    descr("Prints out a dump of the configuration")

    override def run(configurator: MidoNodeConfigurator) = {
        print(configurator.dropSchema(makeConfig(configurator).resolve()).root().render(renderOpts))
        println()
        ConfCommand.SUCCESS
    }
}

object Doc extends Subcommand("doc") with ConfCommand {
    descr("Query the schema information for a specific configuration key")

    val key = trailArg[String](required = true, descr = "Configuration key")

    override def run(configurator: MidoNodeConfigurator) = {
        val conf = configurator.mergedSchemas().resolve()

        try {
            println(s"Key: ${key.get.get}")

            if (conf.hasPath(s"${key.get.get}_type")) {
                val t = conf.getString(s"${key.get.get}_type")
                println(s"Type: $t")
            }

            val v = conf.getValue(key.get.get).unwrapped()
            println(s"Default value: $v")

            if (conf.hasPath(s"${key.get.get}_description")) {
                val desc = conf.getString(s"${key.get.get}_description")
                println(s"Documentation: $desc")
            }

        } catch {
            case e: ConfigException.Missing => // ignored
        }

        ConfCommand.SUCCESS
    }
}

object GetConf extends ConfigQuery("get") with ConfCommand {
    descr("Query configuration for a specific value")

    val key = trailArg[String](required = true, descr = "Configuration key")

    override def run(configurator: MidoNodeConfigurator) = {
        val conf = makeConfig(configurator).resolve()

        try {
            val v = conf.getValue(key.get.get).render(renderOpts)
            println(s"${key.get.get} = $v")
            ConfCommand.SUCCESS
        } catch {
            case e: ConfigException.Missing => ConfCommand.NO_SUCH_KEY
        }

    }
}

abstract class ConfigQuery(name: String) extends Subcommand(name) {
    import ConfCommand._

    val _renderOpts = ConfigRenderOptions.defaults().setOriginComments(true).
                                                    setComments(true).
                                                    setJson(false).
                                                    setFormatted(true)
    val _schemaRenderOpts = ConfigRenderOptions.defaults().setOriginComments(false).
                                                    setComments(true).
                                                    setJson(false).
                                                    setFormatted(true)

    def renderOpts = if (schema.get.get) _schemaRenderOpts else _renderOpts

    val hostId = opt[String]("host", short = 'h', default = Some("local"), descr =
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
        def stringToUUID(str: String) = if (str == "local")
                                          HostIdGenerator.getHostId()
                                        else
                                          UUID.fromString(str)

        implicit def cfgOptToNaked(opt: Option[Config]): Config = opt.getOrElse(ConfigFactory.empty)

        val hostOpt: Option[UUID] = hostId.get map stringToUUID orElse Option(HostIdGenerator.getHostId())
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
            conf = conf.withFallback(configurator.mergedSchemas)

        conf
    }
}

object MidoConfTool extends App {
    System.setProperty("logback.configurationFile", "logback-disabled.xml")

    val bootstrapConfig = ConfigFactory.parseString("zookeeper.bootstrap_timeout = 1s")

    def date = System.currentTimeMillis()
    val opts = new ScallopConf(args) {

        val bundledConfig = BundledConfig
        val dump = DumpConf
        val deploy = DeployBundledConfig
        val get = GetConf
        val set = SetConf
        val unset = UnsetConf
        val templateGet = GetTemplate
        val templateSet = SetTemplate
        val listTemplate = ListTemplate
        val clearTemplate = ClearTemplate
        val listHosts = ListHosts
        val importConf = ImportConf
        val doc = Doc

        printedName = "mn-conf"
        footer("Copyright (c) 2015 Midokura SARL, All Rights Reserved.")
    }

    def invalidEx = new Exception("invalid arguments, run with --help for usage information")

    val ret = opts.subcommand map {
        case subcommand: ConfCommand =>
            Try(subcommand.run(MidoNodeConfigurator(bootstrapConfig)))
        case e =>
            Failure(invalidEx)
    } getOrElse Failure(invalidEx) match {
        case Success(retcode) =>
            retcode
        case Failure(e) => e match {
            case prop: PropertiesFileNotWritableException =>
                System.err.println("[mn-conf] Failed: this host doesn't yet have a host id assigned and mn-conf failed to generate one.")
                System.err.println(s"[mn-conf] mn-conf failed to store a new host id at ${e.getMessage}.")
                System.err.println("[mn-conf] Please retry as root.")
                2
            case e: KeeperException if e.code() == KeeperException.Code.CONNECTIONLOSS =>
                val zkServer = MidoNodeConfigurator.bootstrapConfig().getString("zookeeper.zookeeper_hosts")
                System.err.println(s"[mn-conf] Could not connect to zookeeper at '$zkServer'")
                System.err.println(s"[mn-conf] Check the FILES and ENVIRONMENT sections of the the mn-conf(1) manual page for details.")
                3
            case other if args.length == 0 =>
                opts.printHelp()
                1
            case other =>
                System.err.println("[mn-conf] Failed: " + e.getMessage)
                4
        }
    }

    System.exit(ret)
}
