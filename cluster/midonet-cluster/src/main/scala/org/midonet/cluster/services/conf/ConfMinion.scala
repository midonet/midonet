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

package org.midonet.cluster.services.conf

import java.util
import java.util.UUID
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import java.util.EnumSet

import com.google.inject.Inject
import com.typesafe.config._
import org.apache.curator.framework.CuratorFramework
import org.eclipse.jetty.server.{DispatcherType, Server}
import org.eclipse.jetty.server.nio.BlockingChannelConnector
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.slf4j.LoggerFactory
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.cluster.services.rest_api.CorsFilter
import org.midonet.conf.MidoNodeConfigurator

@ClusterService(name = "conf")
class ConfMinion @Inject()(nodeContext: ClusterNode.Context,
                           config: ClusterConfig)
    extends Minion(nodeContext) {

    private val log = LoggerFactory.getLogger("org.midonet.conf-service")

    var server: Server = _
    var zk: CuratorFramework = _

    override def isEnabled = config.confApi.isEnabled

    override def doStart(): Unit = {
        val configurator = MidoNodeConfigurator(config.conf)
        if (configurator.deployBundledConfig())
            log.info("Deployed new configuration schemas for cluster MidoNet nodes")

        log.info(s"Starting configuration API service at ${config.confApi.httpPort}")

        server = new Server()
        val http = new BlockingChannelConnector()
        http.setPort(config.confApi.httpPort)
        server.addConnector(http)

        val context = new ServletContextHandler()
        context.setContextPath("/conf")
        context.setClassLoader(Thread.currentThread().getContextClassLoader)
        context.addFilter(classOf[CorsFilter], "/*",
                          util.EnumSet.allOf(classOf[DispatcherType]))
        server.setHandler(context)
        addServletsFor(configurator, context)
        server.start()
        notifyStarted()
    }

    private def addServletsFor(c: MidoNodeConfigurator,
                               ctx: ServletContextHandler): Unit = {
        def addServlet(endpoint: ConfigApiEndpoint, path: String): Unit = {
            log.info(s"Registering config service servlet at /$path")
            ctx.addServlet(new ServletHolder(endpoint), s"/$path")
        }

        addServlet(new SchemaEndpoint(c), "schema")
        addServlet(new PerNodeEndpoint(c), "node/*")
        addServlet(new RuntimeEndpoint(c), "runtime-config/*")
        addServlet(new TemplateEndpoint(c), "template/*")
        addServlet(new TemplateListEndpoint(c), "template-list")
        addServlet(new TemplateMappingsEndpoint(c), "template-mappings/*")
        addServlet(new TemplateMappingsEndpoint(c), "template-mappings")
    }

    override def doStop(): Unit = {
        if (server ne null) {
            server.stop()
            server.join()
        }
        if (zk ne null) {
            zk.close()
        }
        notifyStopped()
    }
}

abstract class ConfigApiEndpoint extends HttpServlet {

    protected val log = LoggerFactory.getLogger("org.midonet.conf-api")

    private val renderOpts = ConfigRenderOptions.defaults().setComments(false).setJson(true).setOriginComments(false)

    protected def render(config: Config) = config.root().render(renderOpts)

    private def tryHandle(req: HttpServletRequest, resp: HttpServletResponse)(
        func: (HttpServletRequest, HttpServletResponse) => Unit): Unit = {

        try {
            func(req, resp)
        } catch {
            case e: ConfigException =>
                resp.setContentType("text/plain; charset=utf-8")
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                resp.getWriter.write(e.getMessage)
                log.info("Bad request: ", e.getMessage)
            case e: Exception =>
                resp.setContentType("text/plain; charset=utf-8")
                resp.setDateHeader("Expires", 0)
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                e.printStackTrace(resp.getWriter)
                log.warn("Resquest failed: ", e)
        }
    }

    override def doGet(req: HttpServletRequest, resp: HttpServletResponse) =
        tryHandle(req, resp){ _get }

    override def doPost(req: HttpServletRequest, resp: HttpServletResponse) =
        tryHandle(req, resp){ _post }

    override def doDelete(req: HttpServletRequest, resp: HttpServletResponse) =
        tryHandle(req, resp){ _delete }

    private def resource(req: HttpServletRequest) = {
        req.getPathInfo match {
            case null => null
            case s => s.stripPrefix("/").trim
        }
    }

    private def _get(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        log.info(s"${req.getMethod} ${req.getRequestURI}")
        val content = getResource(resource(req))
        resp.getWriter.println(render(content))
        resp.setContentType("text/plain; charset=utf-8")
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        resp.setHeader("Pragma", "no-cache")
        resp.setDateHeader("Expires", 0)
        resp.setStatus(HttpServletResponse.SC_OK)
    }

    protected def getResource(name: String): Config

    private def _delete(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        log.info(s"${req.getMethod} ${req.getRequestURI}")
        resp.setStatus(deleteResource(resource(req)))
    }

    protected def deleteResource(name: String): Int = HttpServletResponse.SC_METHOD_NOT_ALLOWED

    private def _post(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        log.info(s"${req.getMethod} ${req.getRequestURI}")
        val newConf = ConfigFactory.parseReader(req.getReader)
        val code = postResource(resource(req), newConf)
        resp.setStatus(code)
        if (code == HttpServletResponse.SC_OK)
            resp.getWriter.println(render(getResource(resource(req))))
    }

    protected def postResource(name: String, content: Config): Int = HttpServletResponse.SC_METHOD_NOT_ALLOWED
}

class TemplateEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint {
    override protected def getResource(name: String) =
        configurator.templateByName(name).closeAfter(_.get)

    override protected def postResource(name: String, content: Config) = {
        configurator.validate(content)
        configurator.templateByName(name).closeAfter(_.mergeAndSet(content))
        HttpServletResponse.SC_OK
    }

    override def deleteResource(name: String): Int = {
        configurator.templateByName(name).closeAfter(_.clearAndSet(ConfigFactory.empty()))
        HttpServletResponse.SC_OK
    }
}

class SchemaEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint {
    override protected def getResource(name: String) = configurator.mergedSchemas()
}

class RuntimeEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint {
    override protected def getResource(name: String) = configurator.centralConfig(UUID.fromString(name))
}

class PerNodeEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint {
    override protected def getResource(name: String) =
        configurator.centralPerNodeConfig(UUID.fromString(name)).closeAfter(_.get)

    override protected def postResource(name: String, content: Config) = {
        configurator.validate(content)
        configurator.centralPerNodeConfig(UUID.fromString(name)).closeAfter(_.mergeAndSet(content))
        HttpServletResponse.SC_OK
    }

    override def deleteResource(name: String): Int = {
        configurator.centralPerNodeConfig(UUID.fromString(name)).closeAfter(_.clearAndSet(ConfigFactory.empty()))
        HttpServletResponse.SC_OK
    }
}

class TemplateMappingsEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint {
    override protected def getResource(name: String) = {
        if (name == null || name.isEmpty || name.equals("/")) {
            configurator.templateMappings
        } else {
            val templateName = configurator.templateNameForNode(UUID.fromString(name))
            ConfigFactory.empty().withValue(name, ConfigValueFactory.fromAnyRef(templateName))
        }
    }

    override def postResource(name: String, content: Config): Int = {
        if (name == null || name.isEmpty || name.equals("/")) {
            HttpServletResponse.SC_BAD_REQUEST
        } else {
            val templateName = content.getString(name)
            configurator.assignTemplate(UUID.fromString(name), templateName)
            HttpServletResponse.SC_OK
        }
    }

    override def deleteResource(name: String): Int = {
        if (name == null || name.isEmpty || name.equals("/")) {
            HttpServletResponse.SC_BAD_REQUEST
        } else {
            val newMappings = configurator.templateMappings.withoutPath(name)
            configurator.updateTemplateAssignments(newMappings)
            HttpServletResponse.SC_OK
        }
    }
}

class TemplateListEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint {
    override protected def getResource(name: String) = {
        val templates = configurator.listTemplates reduceOption ((a, b) => s"$a, $b") getOrElse ""
        ConfigFactory.parseString(s"{ templates : [ $templates ] }")
    }
}
