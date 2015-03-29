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

package org.midonet.brain.services.conf

import java.util.UUID
import javax.servlet.http.{HttpServlet, HttpServletResponse, HttpServletRequest}

import com.google.inject.Inject
import com.typesafe.config.{ConfigFactory, Config, ConfigRenderOptions}
import org.apache.curator.framework.CuratorFramework
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.nio.BlockingChannelConnector
import org.eclipse.jetty.servlet.{ServletHolder, ServletContextHandler}
import org.slf4j.LoggerFactory

import org.midonet.brain.{ClusterMinion, BrainConfig, ClusterNode}
import org.midonet.conf.{ObservableConf, MidoNodeConfigurator}

class ConfMinion @Inject()(nodeContext: ClusterNode.Context, config: BrainConfig)
        extends ClusterMinion(nodeContext) {

    private val log = LoggerFactory.getLogger("org.midonet.conf-service")

    var server: Server = _
    var zk: CuratorFramework = _

    override def doStart(): Unit = {
        log.info(s"Starting configuration API service at ${config.confApi.httpPort}")

        zk = MidoNodeConfigurator.zkBootstrap(config.conf)

        if (MidoNodeConfigurator.forBrains(zk).deployBundledConfig())
            log.info("Deployed new configuration schemas for brain MidoNet nodes")

        server = new Server()
        val http = new BlockingChannelConnector()
        http.setPort(config.confApi.httpPort)
        server.addConnector(http)

        val context = new ServletContextHandler()
        context.setContextPath("/conf")
        context.setClassLoader(Thread.currentThread().getContextClassLoader)
        server.setHandler(context)

        val agentConfigurator = MidoNodeConfigurator.forAgents(zk)
        val brainConfigurator = MidoNodeConfigurator.forBrains(zk)

        context.addServlet(new ServletHolder(
            new TemplateEndpoint(agentConfigurator)), "/agent/template/*")
        context.addServlet(new ServletHolder(
            new TemplateEndpoint(brainConfigurator)), "/brain/template/*")

        context.addServlet(new ServletHolder(
            new PerNodeEndpoint(agentConfigurator)), "/agent/node/*")
        context.addServlet(new ServletHolder(
            new PerNodeEndpoint(brainConfigurator)), "/brain/node/*")

        context.addServlet(new ServletHolder(
            new SchemaEndpoint(agentConfigurator)), "/agent/schema")
        context.addServlet(new ServletHolder(
            new SchemaEndpoint(brainConfigurator)), "/brain/schema")

        context.addServlet(new ServletHolder(
            new RuntimeEndpoint(agentConfigurator)), "/agent/runtime-config/*")
        context.addServlet(new ServletHolder(
            new RuntimeEndpoint(brainConfigurator)), "/brain/runtime-config/*")

        context.addServlet(new ServletHolder(
            new TemplateMappingsEndpoint(agentConfigurator)), "/agent/template-mappings")
        context.addServlet(new ServletHolder(
            new TemplateMappingsEndpoint(brainConfigurator)), "/brain/template-mappings")

        context.addServlet(new ServletHolder(
            new TemplateListEndpoint(agentConfigurator)), "/agent/template-list")
        context.addServlet(new ServletHolder(
            new TemplateListEndpoint(brainConfigurator)), "/brain/template-list")

        server.start()
        notifyStarted()
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

abstract class ConfigApiEndpoint(val configurator: MidoNodeConfigurator) extends HttpServlet {

    protected val log = LoggerFactory.getLogger("org.midonet.conf-api")

    private val renderOpts = ConfigRenderOptions.defaults().setComments(false).setJson(true).setOriginComments(false)

    protected def render(config: Config) = config.root().render(renderOpts)

    private def tryHandle(req: HttpServletRequest, resp: HttpServletResponse)(
        func: (HttpServletRequest, HttpServletResponse) => Unit): Unit = {

        try {
            func(req, resp)
        } catch {
            case e: Exception =>
                resp.setContentType("text/plain")
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                e.printStackTrace(resp.getWriter)
                e.printStackTrace(System.out)
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

class TemplateEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint(configurator) {
    override protected def getResource(name: String) =
        configurator.templateByName(name).closeAfter(_.get)

    override protected def postResource(name: String, content: Config) = {
        configurator.templateByName(name).closeAfter(_.mergeAndSet(content))
        HttpServletResponse.SC_OK
    }

    override def deleteResource(name: String): Int = {
        configurator.templateByName(name).closeAfter(_.clearAndSet(ConfigFactory.empty()))
        HttpServletResponse.SC_OK
    }
}

class SchemaEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint(configurator) {
    override protected def getResource(name: String) = configurator.schema.closeAfter(_.get)
}

class RuntimeEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint(configurator) {
    override protected def getResource(name: String) = configurator.centralConfig(UUID.fromString(name))
}

class PerNodeEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint(configurator) {
    override protected def getResource(name: String) =
        configurator.centralPerNodeConfig(UUID.fromString(name)).closeAfter(_.get)

    override protected def postResource(name: String, content: Config) = {
        configurator.centralPerNodeConfig(UUID.fromString(name)).closeAfter(_.mergeAndSet(content))
        HttpServletResponse.SC_OK
    }

    override def deleteResource(name: String): Int = {
        configurator.centralPerNodeConfig(UUID.fromString(name)).closeAfter(_.clearAndSet(ConfigFactory.empty()))
        HttpServletResponse.SC_OK
    }
}

class TemplateMappingsEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint(configurator) {
    override protected def getResource(name: String) =
        configurator.templateMappings

    override protected def postResource(name: String, content: Config) = {
        configurator.updateTemplateAssignments(content)
        HttpServletResponse.SC_OK
    }
}

class TemplateListEndpoint(configurator: MidoNodeConfigurator) extends ConfigApiEndpoint(configurator) {
    override protected def getResource(name: String) = {
        val templates = configurator.listTemplates reduceOption ((a, b) => s"$a, $b") getOrElse ""
        ConfigFactory.parseString(s"{ templates : [ $templates ] }")
    }
}
