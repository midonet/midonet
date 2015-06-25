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

package org.midonet.cluster.services.rest_api.conf

import java.util.UUID
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import com.typesafe.config._
import org.slf4j.LoggerFactory

import org.midonet.conf.MidoNodeConfigurator

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
