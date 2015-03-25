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
import java.util.zip.ZipInputStream

import scala.collection.JavaConversions._
import scala.util.Try

import com.typesafe.config._
import org.apache.commons.configuration.{HierarchicalConfiguration, HierarchicalINIConfiguration}
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.retry.RetryOneTime
import org.apache.zookeeper.KeeperException
import rx.{Observer, Observable}

import org.midonet.util.functors.makeFunc1
import org.midonet.util.functors.makeFunc2
import org.midonet.cluster.util.ObservableNodeCache

/**
 * A configuration source capable of producing an immutable Config object.
 */
trait MidoConf {
    def get: Config
}

object MidoNodeType {
    val AGENT = "agent"
    val BRAIN = "brain"

    val all = List(AGENT, BRAIN)
}

trait ObservableConf extends MidoConf {
    def observable: Observable[Config]
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
    private val emptySchema = ConfigFactory.empty().withValue(
        "schemaVersion", ConfigValueFactory.fromAnyRef(-1))

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

    /**
     * Write a schema to this config source.
     *
     * Schemas must contain a "schemaVersion" configuration key. The operation
     * will be a no-op if the existing schema version is equal or bigger than
     * the supplied schema.
     *
     * Malformed schemas will make this method throw an exception.
     */
    def setAsSchema(schema: Config): Boolean = modify { oldSchema =>
        val oldV = oldSchema.withFallback(emptySchema).getInt("schemaVersion")
        val newV = schema.getInt("schemaVersion")

        if (newV > oldV)
            schema
        else
            oldSchema
    }

}

object MidoNodeConfigurator {
    def bootstrapConfig(inifile: Option[String] = None): Config = {
        val MIDONET_CONF_LOCATIONS = List("~/.midonetrc", "/etc/midonet/midonet.conf",
            "/etc/midolman/midolman.conf")

        val DEFAULTS = ConfigFactory.parseString(
            """
              |zookeeper.zookeeper_hosts = "127.0.0.1:2181"
              |zookeeper.midolman_root_key = ${zookeeper.root_key}
              |zookeeper.root_key = "/midonet/v1"
              |zookeeper.base_retry = 1s
              |zookeeper.max_retries = 10
              |zookeeper.use_new_stack = false
            """.stripMargin)

        val ENVIRONMENT = ConfigFactory.parseString(
            """
              |zookeeper.zookeeper_hosts = ${?MIDO_ZOOKEEPER_HOSTS}
              |zookeeper.root_key = ${?MIDO_ZOOKEEPER_ROOT_KEY}
            """.stripMargin)

        val locations = (inifile map ( List(_) ) getOrElse Nil) ::: MIDONET_CONF_LOCATIONS

        def loadCfg = (loc: String) => Try(new IniFileConf(loc).get).getOrElse(ConfigFactory.empty)

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
        val zk = CuratorFrameworkFactory.builder().
                    connectString(serverString).
                    connectionTimeoutMs(30*1000).
                    retryPolicy(new RetryOneTime(1000)).
                    namespace(namespace).
                    build()
        zk.start()
        zk
    }

    def forAgents(zk: CuratorFramework, inifile: Option[String] = None): MidoNodeConfigurator =
            new MidoNodeConfigurator(zk, MidoNodeType.AGENT, inifile)

    def forAgents(inifile: String): MidoNodeConfigurator =
        forAgents(zkBootstrap(Option(inifile)), Option(inifile))

    def forAgents(bootstrapConf: Config): MidoNodeConfigurator =
        forAgents(zkBootstrap(bootstrapConf), None)

    def forAgents(): MidoNodeConfigurator = forAgents(zkBootstrap(), None)

    def forBrains(zk: CuratorFramework, inifile: Option[String] = None): MidoNodeConfigurator =
        new MidoNodeConfigurator(zk, MidoNodeType.BRAIN, inifile)

    def forBrains(inifile: String): MidoNodeConfigurator =
        forBrains(zkBootstrap(Option(inifile)), Option(inifile))

    def forBrains(bootstrapConf: Config): MidoNodeConfigurator =
        forBrains(zkBootstrap(bootstrapConf), None)

    def forBrains(): MidoNodeConfigurator = forBrains(zkBootstrap(), None)
}

object MidoTestConfigurator {
    def bootstrap = MidoNodeConfigurator.bootstrapConfig(None)

    def forAgents = new MidoTestConfigurator(MidoNodeType.AGENT).testConfig

    def forAgents(overrides: Config) = new MidoTestConfigurator(
            MidoNodeType.AGENT, overrides).testConfig

    def forAgents(overrides: String) = new MidoTestConfigurator(
            MidoNodeType.AGENT, ConfigFactory.parseString(overrides)).testConfig

    def forBrains = new MidoTestConfigurator(MidoNodeType.BRAIN).testConfig

    def forBrains(overrides: Config) = new MidoTestConfigurator(
        MidoNodeType.BRAIN, overrides).testConfig

    def forBrains(overrides: String) = new MidoTestConfigurator(
        MidoNodeType.BRAIN, ConfigFactory.parseString(overrides)).testConfig
}

class MidoTestConfigurator(val nodeType: String, overrides: Config = ConfigFactory.empty) {
    def testConfig: Config = overrides.withFallback(
            new ResourceConf(s"org/midonet/conf/$nodeType.schema").get).
                withFallback(MidoNodeConfigurator.bootstrapConfig())
}

/**
 * Manages and provides access for all the different configuration sources that
 * make up the configuration of a MidoNet node.
 *
 * @param zk Curator framework connection.
 * @param nodeType Node type. Known types are "agent" and "brain".
 * @param inifile Optional location of a legacy .ini configuration file.
 */
class MidoNodeConfigurator(zk: CuratorFramework,
                           val nodeType: String, inifile: Option[String] = None) {

    private val _templateMappings = new ZookeeperConf(zk, s"/config/template-mappings/$nodeType")

    {
        val zkClient = zk.getZookeeperClient
        zk.newNamespaceAwareEnsurePath(s"/config/$nodeType").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/templates/$nodeType").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/schemas/$nodeType").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/template-mappings").ensure(zkClient)
    }

    /**
     * Returns a Config object composed solely of local configuration sources.
     *
     * These sources are system properties, environment variables and
     * configuration files.
     */
    def localOnlyConfig: Config = ConfigFactory.systemEnvironment().
            withFallback(inifile map { new IniFileConf(_).get } getOrElse ConfigFactory.empty)

    /**
     * Returns the WritableConf that points to the centrally stored configuration
     * specific to a particular MidoNet node.
     *
     * @param node The node id
     * @return
     */
    def centralPerNodeConfig(node: UUID): WritableConf with ObservableConf =
        new ZookeeperConf(zk, s"/config/$nodeType/$node")

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
        new ZookeeperConf(zk, s"/config/templates/$nodeType/$name")


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
     * Assings a configuration template to a MidoNet node.
     */
    def assignTemplate(node: UUID, template: String): Unit = {
        val value = ConfigValueFactory.fromAnyRef(template)
        _templateMappings.set(node.toString, value)
    }

    def listTemplates: Seq[String] = zk.getChildren.forPath(s"/config/templates/$nodeType")

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
    def schema: WritableConf with ObservableConf =
        new ZookeeperConf(zk, s"/config/schemas/$nodeType")

    /**
     * Returns the Config object composed of all the server-side configuration
     * sources that make up the configuration for a given MidoNet node. These
     * sources are the node-specific configuration, its assigned template,
     * the 'default' template, and the schema.
     */
    def centralConfig(node: UUID): Config =
        centralPerNodeConfig(node).get.
            withFallback(templateByNodeId(node).get).
            withFallback(templateByName("default").get).
            withFallback(schema.get)

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
                        combine(templateByName("default"), schema)))
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
        localOnlyConfig.withFallback(centralConfig(node)).
                        withFallback(MidoNodeConfigurator.bootstrapConfig(inifile)).
                        withFallback(bundledSchema.get)

    /**
     * Return an Observable on the runtime configuration that a particular
     * node must use at runtime.
     */
    def observableRuntimeConfig(node: UUID): Observable[Config] = {
        combine(Observable.just(localOnlyConfig),
                combine(observableCentralConfig(node),
                        Observable.just(MidoNodeConfigurator.bootstrapConfig(inifile).
                                        withFallback(bundledSchema.get))))
    }

    /**
     * Returns the MidoConf object that points to the schema bundled with the
     * running application.
     */
    def bundledSchema: MidoConf = new ResourceConf(s"org/midonet/conf/$nodeType.schema")

    private def deploySchema(): Boolean = {
        val newSchema = bundledSchema.get
        if (!newSchema.isEmpty)
            schema.setAsSchema(newSchema)
        else
            false
    }

    private def extractTemplatesFromJar(uri: URI): List[(String, ResourceConf)] = {
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

    private def extractTemplatesFromDir(uri: URI): List[(String, ResourceConf)] = {
        var templates: List[(String, ResourceConf)] = List.empty
        val dir = new File(uri)
        for (child <- dir.listFiles if child.getName.endsWith(".conf")) {
            val fn = child.getName
            val templateName = fn.substring(0, fn.lastIndexOf('.'))
            templates ::= (templateName, new ResourceConf(child.getCanonicalPath))
        }
        templates
    }

    def bundledTemplates: Seq[(String, MidoConf)] = {
        val path = s"org/midonet/conf/templates/$nodeType"
        val urls = getClass. getClassLoader.getResources(path)

        if (urls.hasMoreElements) {
            val uri = urls.nextElement.toURI
            if (uri.toString.startsWith("jar:"))
                extractTemplatesFromJar(uri)
            else
                extractTemplatesFromDir(uri)
        } else {
            List.empty
        }
    }

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
        val isUpdate = deploySchema()
        if (isUpdate) {
            for ((name, conf) <- bundledTemplates) {
                templateByName(name).clearAndSet(conf.get)
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
    val parseOpts = ConfigParseOptions.defaults().
        setAllowMissing(false).
        setOriginDescription(s"resource:$path").
        setSyntax(ConfigSyntax.CONF)

    override def get: Config = try {
        val stream = getClass.getClassLoader.getResourceAsStream(path)

        ConfigFactory.parseReader(new InputStreamReader(stream), parseOpts)
    } catch {
        case e: Exception =>
            e.printStackTrace()
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
        parse(cache.current).resolve()
    }

    override protected def modify(changeset: Config => Config): Boolean = {
        val zkdata = cache.current
        try {
            if (zkdata eq null) {
                val conf = changeset(ConfigFactory.empty)
                if (!conf.isEmpty) {
                    val newconf = conf.root().render(renderOpts).getBytes
                    zk.create().creatingParentsIfNeeded().forPath(path, newconf)
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
                    zk.setData().withVersion(version).forPath(path, newconfStr)
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

/**
 * A MidoConf implementation backed by a .ini configuration file.
 *
 * @param filename
 */
class IniFileConf(val filename: String) extends MidoConf {

    private val iniconf: HierarchicalINIConfiguration = new HierarchicalINIConfiguration()
    iniconf.setDelimiterParsingDisabled(true)
    iniconf.setFileName(filename)
    iniconf.setThrowExceptionOnMissing(false)
    iniconf.load()

    def get: Config = {
        var config = ConfigFactory.empty()
        for (section <- iniconf.getSections;
             key <- iniconf.getSection(section).getKeys) {
            val newkey = s"$section.$key"
            val newval = iniconf.getSection(section).getString(key)

            config = config.withValue(newkey,
                ConfigValueFactory.fromAnyRef(newval, s"file://$filename"))
        }

        config
    }
}


/**
 * A MidoConf implementation backed by a HierarchicalConfiguration object
 */
class LegacyConf(val hconf: HierarchicalConfiguration) extends MidoConf {
    def get: Config = {
        var config = ConfigFactory.empty()
        for (key <- hconf.getKeys) {
            val newval = hconf.getProperty(key)
            config = config.withValue(key,
                ConfigValueFactory.fromAnyRef(newval, s"legacy conf"))
        }
        config
    }
}
