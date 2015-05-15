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

import java.io._
import java.net.{URL, URI}
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

import scala.collection.JavaConversions._
import scala.util.Try

import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import org.apache.commons.configuration.HierarchicalINIConfiguration
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.retry.RetryOneTime
import org.apache.zookeeper.KeeperException
import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.conf.MidoConf._
import org.midonet.util.functors.makeFunc1
import org.midonet.util.functors.makeFunc2
import org.midonet.cluster.util.ObservableNodeCache

/**
 * A configuration source capable of producing an immutable Config object.
 */
trait MidoConf {
    def get: Config
}

object MidoConf {
    implicit def toConfigValue(v: String): ConfigValue = ConfigValueFactory.fromAnyRef(v)
    implicit def toConfigValue(v: Int): ConfigValue = ConfigValueFactory.fromAnyRef(v)
    implicit def toConfigValue(v: Boolean): ConfigValue = ConfigValueFactory.fromAnyRef(v)

    implicit def strTupleToConfigValue(v: (String, String)): ConfigValue =
        ConfigValueFactory.fromAnyRef(v._1, v._2)
    implicit def intTupleToConfigValue(v: (Int, String)): ConfigValue =
        ConfigValueFactory.fromAnyRef(v._1, v._2)
    implicit def boolTupleToConfigValue(v: (Boolean, String)): ConfigValue =
        ConfigValueFactory.fromAnyRef(v._1, v._2)
}

trait ObservableConf extends Closeable {
    def observable: Observable[Config]

    def closeAfter[T](func: (this.type) => T): T = try {
        func(this)
    } finally {
        close()
    }
}

trait Schema extends WritableConf {
    def version: Int
    def schemaName: String
    def setAsSchema(schema: Config): Boolean
}

/**
 * A writable configuration source. All write operations will commit the
 * requested changes to the underlying configuration source before
 * returning.
 *
 * Note that the get() method in MidoConf returns immutable objects. Thus,
 * Config objects retrieved prior to write operation will remain unchanged.
 */
trait WritableConf extends MidoConf {
    protected def modify(changeset: Config => Config): Boolean

    /**
     * Delete a configuration key.
     */
    def unset(key: String): Unit = modify { _.withoutPath(key) }

    /**
     * Sets a configuration key.
     */
    def set(key: String, value: ConfigValue): Unit = modify { _.withValue(key, value) }

    /**
     * Merges the contents of the given Config object on top of the existing
     * configuration.
     */
    def mergeAndSet(config: Config): Unit = modify { config.withFallback }

    /**
     * Clear the previously existing configuration put the contents of the
     * given Config object in its place.
     */
    def clearAndSet(config: Config): Unit = modify { _ => config }
}

object MidoNodeConfigurator {
    def bootstrapConfig(inifile: Option[String] = None): Config = {
        val MIDONET_CONF_LOCATIONS = List("~/.midonetrc", "/etc/midonet/midonet.conf",
            "/etc/midolman/midolman.conf")

        val DEFAULTS = ConfigFactory.parseString(
            """
            |zookeeper {
            |    zookeeper_hosts = "127.0.0.1:2181"
            |    root_key : "/midonet/v1"
            |    midolman_root_key = ${zookeeper.root_key}
            |    bootstrap_timeout = 30s
            |}
            """.stripMargin)

        val ENVIRONMENT = ConfigFactory.parseString(
            """
              |zookeeper.zookeeper_hosts = ${?MIDO_ZOOKEEPER_HOSTS}
              |zookeeper.root_key = ${?MIDO_ZOOKEEPER_ROOT_KEY}
            """.stripMargin)

        val locations = (inifile map ( List(_) ) getOrElse Nil) ::: MIDONET_CONF_LOCATIONS

        def loadCfg = (loc: String) => Try(new LegacyConf(loc).get).getOrElse(ConfigFactory.empty)

        ENVIRONMENT.withFallback({ for (l <- locations) yield loadCfg(l)
        } reduce((a, b) => a.withFallback(b))
            withFallback(ConfigFactory.systemProperties)
            withFallback(DEFAULTS)).resolve()
    }

    def zkBootstrap(inifile: Option[String] = None): CuratorFramework =
        zkBootstrap(bootstrapConfig(inifile))

    def zkBootstrap(cfg: Config): CuratorFramework = {
        val serverString = cfg.getString("zookeeper.zookeeper_hosts")

        val namespace = cfg.getString("zookeeper.root_key").stripPrefix("/")
        val timeoutMillis = cfg.getDuration("zookeeper.bootstrap_timeout",
                                            TimeUnit.MILLISECONDS)
        val zk = CuratorFrameworkFactory.builder().
                    connectString(serverString).
                    connectionTimeoutMs(timeoutMillis.toInt).
                    retryPolicy(new RetryOneTime(100)).
                    namespace(namespace).
                    build()
        zk.start()
        zk
    }

    def apply(zk: CuratorFramework, inifile: Option[String] = None): MidoNodeConfigurator =
            new MidoNodeConfigurator(zk, inifile)

    def apply(inifile: String): MidoNodeConfigurator =
        apply(zkBootstrap(Option(inifile)), Option(inifile))

    def apply(bootstrapConf: Config): MidoNodeConfigurator =
        apply(zkBootstrap(bootstrapConf.withFallback(bootstrapConfig())), None)

    def apply(): MidoNodeConfigurator = apply(zkBootstrap())
}

object MidoTestConfigurator {
    def bootstrap = MidoNodeConfigurator.bootstrapConfig(None)

    def forAgents(): Config = forAgents(ConfigFactory.empty)

    def forAgents(overrides: String): Config = forAgents(ConfigFactory.parseString(overrides))

    def forAgents(overrides: Config): Config = {
        overrides.withFallback(
            new ResourceConf(s"org/midonet/conf/schemas/agent.conf").get).withFallback(
                new ResourceConf(s"org/midonet/conf/schemas/nsdb.conf").get).withFallback(
                    MidoNodeConfigurator.bootstrapConfig())
    }

    def forClusters(): Config = forClusters(ConfigFactory.empty)

    def forClusters(overrides: String): Config = forClusters(ConfigFactory.parseString(overrides))

    def forClusters(overrides: Config): Config = {
        overrides.withFallback(
            new ResourceConf(s"org/midonet/conf/schemas/cluster.conf").get).withFallback(
                new ResourceConf(s"org/midonet/conf/schemas/nsdb.conf").get).withFallback(
                    MidoNodeConfigurator.bootstrapConfig())
    }
}

/**
 * Manages and provides access for all the different configuration sources that
 * make up the configuration of a MidoNet node.
 *
 * @param zk Curator framework connection.
 * @param agentLegacyIniFile Optional location of a legacy .ini configuration file.
 */
class MidoNodeConfigurator(zk: CuratorFramework,
                           agentLegacyIniFile: Option[String] = Some("/etc/midolman/midolman.conf")) {
    val log = Logger(LoggerFactory.getLogger("org.midonet.conf"))

    private val _templateMappings = new ZookeeperConf(zk, s"/config/template-mappings")

    {
        val zkClient = zk.getZookeeperClient
        zk.newNamespaceAwareEnsurePath(s"/config").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/nodes").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/templates").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/schemas").ensure(zkClient)
    }

    def legacyConfigFile: Config = {
        agentLegacyIniFile map (new LegacyConf(_).get)
    }.getOrElse(ConfigFactory.empty)

    /**
     * Returns a Config object composed solely of local configuration sources.
     *
     * These sources are system properties, environment variables and
     * configuration files.
     */
    def localOnlyConfig: Config = ConfigFactory.systemEnvironment().withFallback(legacyConfigFile)

    /**
     * Returns the WritableConf that points to the centrally stored configuration
     * specific to a particular MidoNet node.
     *
     * @param node The node id
     * @return
     */
    def centralPerNodeConfig(node: UUID): WritableConf with ObservableConf =
        new ZookeeperConf(zk, s"/config/nodes/$node")

    /**
     * Returns the WritableConf that points to the configuration template assigned
     * to a given MidoNet node.
     *
     * @param node The node id.
     * @return
     */
    def templateByNodeId(node: UUID): WritableConf with ObservableConf =
        templateByName(templateNameForNode(node))

    /**
     * Returns the WritableConf that points to a given configuration template.
     *
     * @param name The template name
     * @return
     */
    def templateByName(name: String): WritableConf with ObservableConf =
        new ZookeeperConf(zk, s"/config/templates/$name")


    private def mappingFor(node: UUID, mappings: Config): String = {
        if (mappings.hasPath(node.toString))
            mappings.getString(node.toString)
        else
            "default"
    }

    /**
     * Returns the configuration template name assigned to a given MidoNet node.
     *
     * @param node The node id
     * @return
     */
    def templateNameForNode(node: UUID): String = mappingFor(node, templateMappings)

    /**
     * Returns the Config object that holds the list of configuration template
     * assignments to each specific MidoNet node.
     */
    def templateMappings: Config = _templateMappings.get

    /**
     * Assigns a configuration template to a MidoNet node.
     */
    def assignTemplate(node: UUID, template: String): Unit = {
        _templateMappings.set(node.toString, template)
    }

    def listTemplates: Seq[String] = zk.getChildren.forPath(s"/config/templates")

    def listSchemas: Seq[String] = zk.getChildren.forPath(s"/config/schemas")

    def mergedSchemas(): Config = {
        (listSchemas map (schema(_).closeAfter(_.get))).
            fold(ConfigFactory.empty)((a, b) => a.withFallback(b))
    }

    def observableMergedSchemas(): Observable[Config] = {
        (listSchemas map (schema(_).observable)).
            fold(Observable.just(ConfigFactory.empty))((a, b) => combine(a, b))
    }

    private def validateKey(schema: Config, conf: Config, key: String): Unit = {
        if (schema.hasPath(s"${key}_type")) {
            schema.getString(s"${key}_type") match {
                case "duration" => conf.getDuration(key, TimeUnit.MILLISECONDS)
                case "duration[]" => conf.getDurationList(key, TimeUnit.MILLISECONDS)

                case "bool" => conf.getBoolean(key)
                case "bool[]" => conf.getBooleanList(key)

                case "string" => conf.getString(key)
                case "string[]" => conf.getStringList(key)

                case "int" => conf.getInt(key)
                case "int[]" => conf.getIntList(key)

                case "size" => conf.getBytes(key)
                case "size[]" => conf.getBytesList(key)

                case "double" => conf.getDouble(key)
                case "double[]" => conf.getDoubleList(key)
            }
        } else {
            val newVal = conf.getValue(key)
            val schemaVal = schema.getValue(key)
            if (!newVal.valueType().equals(schemaVal.valueType()))
                throw new ConfigException.WrongType(newVal.origin(),
                    s"Value for $key (${newVal.render()}}) does " +
                        s"not follow schema type (${schemaVal.render()}})")
        }
    }

    /**
     * Validates a configuration snippet against the schemas. Returns a list
     * of keys that are missing from the schemas and thus could not be validated
     */
    def validate(newConf: Config): Seq[String] = {
        val schemas = mergedSchemas().resolve()
        val validationConf = newConf.withFallback(schemas).resolve()
        var unverified: List[String] = List.empty

        for (entry <- newConf.entrySet) {
            try {
                validateKey(schemas, validationConf, entry.getKey)
            } catch {
                case e: ConfigException.Missing => unverified ::= entry.getKey
            }
        }

        unverified
    }

    /**
     * Updates all the template assignments
     */
    def updateTemplateAssignments(mappings: Config): Unit = {
        _templateMappings.clearAndSet(mappings)
    }

    def observableTemplateForNode(node: UUID): Observable[Config] = {
        val templateName = _templateMappings.observable.map[String](makeFunc1{mappingFor(node, _)})
        val template = templateName.distinctUntilChanged().map[Observable[Config]](makeFunc1{ templateByName(_).observable })
        Observable.switchOnNext(template)
    }

    /**
     * Returns the WritableConf that points to the configuration schema for
     * the node type managed by this configurator.
     */
    def schema(name: String): Schema with ObservableConf =
        new ZookeeperSchema(zk, s"/config/schemas/$name", name)

    /**
     * Returns the Config object composed of all the server-side configuration
     * sources that make up the configuration for a given MidoNet node. These
     * sources are the node-specific configuration, its assigned template,
     * the 'default' template, and the schema.
     */
    def centralConfig(node: UUID): Config =
        centralPerNodeConfig(node).closeAfter(_.get).
            withFallback(templateByNodeId(node).closeAfter(_.get)).
            withFallback(templateByName("default").closeAfter(_.get)).
            withFallback(mergedSchemas())

    private def combine(c1: Observable[Config], c2: Observable[Config]): Observable[Config] = {
        Observable.combineLatest(c1, c2,
            makeFunc2((a: Config, b: Config) => a.withFallback(b)))
    }

    /**
     * Returns an Observable on the server-stored configuration for a
     * particular node. The observable will emit new Config objects any
     * time any of the sources that make up the configuration is updated.
     * This works across changes to the template assignment for the given
     * node.
     */
    def observableCentralConfig(node: UUID): Observable[Config] = {
        implicit def unwrap(o: ObservableConf): Observable[Config] = o.observable

        combine(centralPerNodeConfig(node),
                combine(observableTemplateForNode(node),
                        combine(templateByName("default"),
                                observableMergedSchemas())))
    }

    /**
     * Returns the Config object that a given MidoNet node must use at runtime.
     * It's composed, in this order of preference, of:
     *
     *   - The local configuration sources.
     *   - The server-side configuration sources
     *   - The schema bundled in the application's jars.
     */
    def runtimeConfig(node: UUID): Config =
        localOnlyConfig.
            withFallback(centralConfig(node)).
            withFallback(mergedBundledSchemas).resolve()

    def runtimeConfig: Config = runtimeConfig(HostIdGenerator.getHostId)

    /**
     * Return an Observable on the runtime configuration that a particular
     * node must use at runtime.
     */
    def observableRuntimeConfig(node: UUID): Observable[Config] = {
        combine(Observable.just(localOnlyConfig),
                combine(observableCentralConfig(node),
                        Observable.just(mergedBundledSchemas))) map makeFunc1(_.resolve())
    }

    /**
     * Returns the MidoConf object that points to the schema bundled with the
     * running application.
     */
    def bundledSchema(name: String): MidoConf =
        new ResourceConf(s"org/midonet/conf/$name.conf")

    def mergedBundledSchemas: Config = {
        (for ((_, s) <- bundledSchemas) yield s.get).
            fold(ConfigFactory.empty)((a, b) => a.withFallback(b))
    }

    private def deploySchemas(): Boolean = {
        var ret = false
        for ((name, newSchema) <- bundledSchemas) {
            val newConfig = newSchema.get
            if (!newConfig.isEmpty) {
                val newVersion = newConfig.getInt(s"$name.schemaVersion")
                val zkSchema = schema(name)
                if (zkSchema.setAsSchema(newConfig)) {
                    log.info(s"Deployed schema $name with version $newVersion")
                    ret = true
                } else {
                    log.info(s"$name schema is up to date at version ${zkSchema.version}")
                }
            }
        }
        ret
    }

    private def extractTemplatesFromJar(uri: URI): List[(String, MidoConf)] = {
        var templates: List[(String, ResourceConf)] = List.empty

        // starts with jar:
        val jarFile = uri.toString.substring(4, uri.toString.lastIndexOf('!'))
        val jarUrl = new URL(jarFile)
        val parts = uri.toString.split("!")
        if (parts.length < 2)
            return List.empty
        // remove leading '/' as jar entries do not have it
        val prefix = parts(1).substring(1)

        val zip = new ZipInputStream(jarUrl.openStream())
        try {
            while(true) {
                val e = zip.getNextEntry
                if (e == null)
                    return templates

                if (e.getName.startsWith(prefix) && !e.isDirectory) {
                    val name = e.getName.substring(e.getName.lastIndexOf('/') + 1,
                                                   e.getName.lastIndexOf('.'))
                    templates ::= (name, new ResourceConf(e.getName))
                }
                zip.closeEntry()
            }
        } finally {
            zip.close()
        }
        templates
    }

    private def extractTemplatesFromDir(uri: URI): List[(String, MidoConf)] = {
        var templates: List[(String, MidoConf)] = List.empty
        val dir = new File(uri)
        for (child <- dir.listFiles if child.getName.endsWith(".conf")) {
            val fn = child.getName
            val templateName = fn.substring(0, fn.lastIndexOf('.'))
            templates ::= (templateName, new FileConf(child.getAbsoluteFile))
        }
        templates
    }

    private def bundledConfigSources(sourceType: String): Seq[(String, MidoConf)] = {
        val path = s"org/midonet/conf/$sourceType"
        val urls = getClass.getClassLoader.getResources(path)

        var sources: List[(String, MidoConf)] = Nil

        while (urls.hasMoreElements) {
            val uri = urls.nextElement.toURI
            if (uri.toString.startsWith("jar:"))
                sources :::= extractTemplatesFromJar(uri)
            else
                sources :::= extractTemplatesFromDir(uri)
        }

        sources
    }

    def bundledTemplates: Seq[(String, MidoConf)] = bundledConfigSources("templates")

    def bundledSchemas: Seq[(String, MidoConf)] = bundledConfigSources("schemas")

    /**
     * Reads the schema and configuration templates that are bundled with the
     * running application and tries to deploy them to the server side.
     *
     * Schemas are deployed as long as they constitute an update.
     *
     * Templates are deployed, overwriting old templates, if the schema was itself
     * an update.
     */
    def deployBundledConfig(): Boolean = {
        val isUpdate = deploySchemas()
        if (isUpdate) {
            for ((name, conf) <- bundledTemplates) {
                templateByName(name).clearAndSet(conf.get)
                log.info(s"Deployed config template: $name")
            }
        }
        isUpdate
    }
}

/**
 * A MidoConf implmentation that reads from bundled Java resources.
 *
 * @param path
 */
class ResourceConf(path: String) extends MidoConf {
    val log = Logger(LoggerFactory.getLogger("org.midonet.conf"))

    val parseOpts = ConfigParseOptions.defaults().
        setAllowMissing(false).
        setOriginDescription(s"resource:$path").
        setSyntax(ConfigSyntax.CONF)

    override def get: Config = try {
        val stream = getClass.getClassLoader.getResourceAsStream(path)
        ConfigFactory.parseReader(new InputStreamReader(stream), parseOpts)
    } catch {
        case e: Exception =>
            log.warn(s"Failed to load config from resource: $path", e)
            ConfigFactory.empty()
    }
}

/**
 * A MidoConf implementation that reads configuration from a file
 *
 * @param file
 */
class FileConf(file: File) extends MidoConf {
    val log = Logger(LoggerFactory.getLogger("org.midonet.conf"))

    val parseOpts = ConfigParseOptions.defaults().
        setAllowMissing(false).
        setOriginDescription(s"file://${file.getAbsolutePath}").
        setSyntax(ConfigSyntax.CONF)

    override def get: Config = try {
        ConfigFactory.parseFile(file)
    } catch {
        case e: Exception =>
            log.warn(s"Failed to load config from file: ${file.getAbsolutePath}", e)
            ConfigFactory.empty()
    }
}

/**
 * A WritableConf implementation backed by ZooKeeper.
 *
 * @param zk
 * @param path
 */
class ZookeeperConf(zk: CuratorFramework, path: String) extends MidoConf with
                                                                WritableConf with
                                                                ObservableConf {

    private val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).
                                                            setComments(false).
                                                            setJson(false)
    private val parseOpts = ConfigParseOptions.defaults().
        setAllowMissing(false).
        setOriginDescription(s"zookeeper://${zk.getNamespace}$path")

    private val cache = new ObservableNodeCache(zk, path, emitNoNodeAsEmpty = true)
    cache.connect()

    private def parse(zkdata: ChildData): Config = {
        if ((zkdata eq null) || (zkdata.getData eq  null)) {
            ConfigFactory.empty()
        } else {
            val data = new String(zkdata.getData)
            val reader = new StringReader(data)
            val conf = ConfigFactory.parseReader(reader, parseOpts)
            reader.close()
            conf
        }
    }

    override val observable: Observable[Config] = cache.observable map makeFunc1(parse)

    override def get: Config = {
        parse(cache.current)
    }

    override def close() = cache.close()

    private def updateAndBlockUntil(cond: ChildData => Boolean)(update: => Unit) {
        val iter = cache.observable.filter(makeFunc1(cond(_))).
                toBlocking.latest().iterator()
        update
        iter.next()
    }

    override protected def modify(changeset: Config => Config): Boolean = {
        val zkdata = cache.current
        try {
            if (zkdata eq null) {
                val conf = changeset(ConfigFactory.empty)
                if (!conf.isEmpty) {
                    val newconf = conf.root().render(renderOpts).getBytes
                    updateAndBlockUntil(_.getStat ne null) {
                        zk.create().creatingParentsIfNeeded().forPath(path, newconf)
                    }
                    true
                } else {
                    false
                }
            } else {
                val version = zkdata.getStat.getVersion
                val oldConf = parse(zkdata)
                val conf = changeset(oldConf)
                if (conf ne oldConf) {
                    val newconfStr = conf.root().render(renderOpts).getBytes
                    updateAndBlockUntil(_.getStat.getVersion > version) {
                        zk.setData().withVersion(version).forPath(path, newconfStr)
                    }
                    true
                } else {
                    false
                }
            }
        } catch {
            case e: KeeperException.BadVersionException => modify(changeset)
            case e: KeeperException.NoNodeException => modify(changeset)
            case e: KeeperException.NodeExistsException => modify(changeset)
        }
    }
}

class ZookeeperSchema(zk: CuratorFramework, path: String,
        override val schemaName: String) extends ZookeeperConf(zk, path) with Schema {

    private val VERSION_KEY = s"$schemaName.schemaVersion"
    private val EMPTY = ConfigFactory.empty().withValue(VERSION_KEY, -1)

    def version = Try(get.getInt(VERSION_KEY)).getOrElse(-1)

    /**
     * Write a schema to this config source.
     *
     * Schemas must contain a "$schenaName.schemaVersion" configuration key. The
     * operation will be a no-op if the existing schema version is equal or
     * bigger than the supplied schema.
     *
     * Malformed schemas will make this method throw an exception.
     */
    def setAsSchema(schema: Config): Boolean = modify { oldSchema =>
        val oldV = oldSchema.withFallback(EMPTY).getInt(VERSION_KEY)
        val newV = schema.getInt(VERSION_KEY)

        if (newV > oldV)
            schema
        else
            oldSchema
    }
}

/**
 * A MidoConf implementation backed by a .ini configuration file.
 *
 * @param filename
 */
class LegacyConf(val filename: String) extends MidoConf {

    val log = Logger(LoggerFactory.getLogger("org.midonet.conf"))

    private val iniconf: HierarchicalINIConfiguration = new HierarchicalINIConfiguration()
    iniconf.setDelimiterParsingDisabled(true)
    iniconf.setFileName(filename)
    iniconf.setThrowExceptionOnMissing(false)
    iniconf.load()

    private val movedDurations: Map[String, (String, TimeUnit)] = Map(
            "zookeeper.session_timeout" ->
                ("zookeeper.session_timeout", TimeUnit.MILLISECONDS),
            "zookeeper.session_gracetime" ->
                ("zookeeper.session_gracetime", TimeUnit.MILLISECONDS),
            "agent.bridge.mac_port_mapping_expire_millis" ->
                ("agent.bridge.mac_port_mapping_expire", TimeUnit.MILLISECONDS),
            "agent.arptable.arp_retry_interval_seconds" ->
                ("agent.arptable.arp_retry_interval", TimeUnit.SECONDS),
            "agent.arptable.arp_timeout_seconds" ->
                ("agent.arptable.arp_timeout", TimeUnit.SECONDS),
            "agent.arptable.arp_stale_seconds" ->
                ("agent.arptable.arp_stale", TimeUnit.SECONDS),
            "agent.arptable.arp_expiration_seconds" ->
                ("agent.arptable.arp_expiration", TimeUnit.SECONDS),
            "agent.midolman.bgp_keepalive" ->
                ("agent.midolman.bgp_keepalive", TimeUnit.SECONDS),
            "agent.midolman.bgp_holdtime" ->
                ("agent.midolman.bgp_holdtime", TimeUnit.SECONDS),
            "agent.midolman.bgp_connect_retry" ->
                ("agent.midolman.bgp_connect_retry", TimeUnit.SECONDS))

    private def translate(key: String, value: String): (String, String) = {
        movedDurations.get(key) match {
            case Some((newkey, TimeUnit.MILLISECONDS)) =>
                val newval = if (value.endsWith("ms")) value else s"${value}ms"
                log.info(s"$filename: Translated legacy configuration value from " +
                         s"{$key : $value} to { $newkey : $newval }")
                (newkey, newval)

            case Some((newkey, TimeUnit.SECONDS)) =>
                val newval = if (value.endsWith("s")) value else s"${value}s"
                log.info(s"$filename: Translated legacy configuration value from " +
                    s"{$key : $value} to { $newkey : $newval }")
                (newkey, newval)

            case Some((newkey, _)) => (key, value) // other time units unused

            case None => (key, value)
        }
    }

    def get: Config = {
        def makeKey(key: String) =
            if (key.startsWith("zookeeper.") ||
                    key.startsWith("cassandra.")) key else s"agent.$key"

        var config = ConfigFactory.empty()
        for (section <- iniconf.getSections;
             key <- iniconf.getSection(section).getKeys) {

            val (newkey, newval) = translate(
                    makeKey(s"$section.$key"),
                    iniconf.getSection(section).getString(key))

            log.info(s"$filename: found legacy config key { $newkey : $newval }")
            config = config.withValue(newkey, (newval, s"file://$filename"))
        }

        config
    }
}
