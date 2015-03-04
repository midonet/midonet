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

import com.typesafe.config._
import org.apache.commons.configuration.HierarchicalINIConfiguration
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{ChildData, NodeCache}
import org.apache.zookeeper.KeeperException


trait MidoConf {
    def get: Config
}

trait WritableConf extends MidoConf {
    private val emptySchema = ConfigFactory.empty().withValue(
        "schemaVersion", ConfigValueFactory.fromAnyRef(-1))

    protected def modify(changeset: Config => Config): Boolean

    def unset(key: String): Unit = modify { _.withoutPath(key) }

    def set(key: String, value: ConfigValue): Unit = modify { _.withValue(key, value) }

    def mergeAndSet(config: Config): Unit = modify { config.withFallback }

    def clearAndSet(config: Config): Unit = modify { _ => config }

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
    def forAgents(zk: CuratorFramework) =
            new MidoNodeConfigurator(zk, "agent", Some("/etc/midolman/midolman.conf"))

}

class MidoNodeConfigurator(zk: CuratorFramework,
                           val nodeType: String, inifile: Option[String] = None) {

    private val _templateMappings = new ZookeeperConf(zk, s"/config/templates/$nodeType-mappings")

    {
        val zkClient = zk.getZookeeperClient
        zk.newNamespaceAwareEnsurePath(s"/config/$nodeType").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/templates/$nodeType").ensure(zkClient)
        zk.newNamespaceAwareEnsurePath(s"/config/schemas/$nodeType").ensure(zkClient)
    }

    def localOnlyConfig: Config = ConfigFactory.systemEnvironment().
            withFallback(ConfigFactory.systemProperties()).
            withFallback(inifile map { new IniFileConf(_).get } getOrElse ConfigFactory.empty)

    def centralPerNodeConfig(node: UUID): ZookeeperConf =
        new ZookeeperConf(zk, s"/config/$nodeType/$node")

    def templateByNodeId(node: UUID): WritableConf = templateByName(templateNameForNode(node))

    def templateByName(name: String): WritableConf =
        new ZookeeperConf(zk, s"/config/templates/$nodeType/$name")

    def templateNameForNode(node: UUID): String = {
        val mappings: Config = templateMappings
        if (mappings.hasPath(node.toString))
            mappings.getString(node.toString)
        else
            "default"
    }

    def templateMappings = _templateMappings.get

    def assignTemplate(node: UUID, template: String): Unit = {
        val value = ConfigValueFactory.fromAnyRef(template)
        _templateMappings.set(node.toString, value)
    }

    def schema: WritableConf = new ZookeeperConf(zk, s"/config/schemas/$nodeType")

    def centralConfig(node: UUID): Config =
        centralPerNodeConfig(node).get.
            withFallback(templateByNodeId(node).get).
            withFallback(templateByName("default").get).
            withFallback(schema.get)

    def runtimeConfig(node: UUID): Config =
        localOnlyConfig.withFallback(centralConfig(node))

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

class ZookeeperConf(zk: CuratorFramework, path: String) extends MidoConf with WritableConf {
    private val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).
                                                            setComments(false).
                                                            setJson(false)

    private val zknode: NodeCache = new NodeCache(zk, path)
    private val parseOpts = ConfigParseOptions.defaults().
                    setAllowMissing(false).
                    setOriginDescription(s"zookeeper:$path")

    zknode.start(true)

    private def parse(zkdata: ChildData): Config = {
        if (zkdata eq null) {
            ConfigFactory.empty()
        } else {
            val data = new String(zkdata.getData)
            val reader = new StringReader(data)
            val conf = ConfigFactory.parseReader(reader, parseOpts)
            reader.close()
            conf
        }
    }

    override def get: Config = {
        parse(zknode.getCurrentData).resolve()
    }

    override protected def modify(changeset: Config => Config): Boolean = {
        val zkdata = zknode.getCurrentData

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
