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

package org.midonet.brain.services.rest_api

import javax.ws.rs.core.Context
import javax.ws.rs.ext.Provider

import com.google.inject.Inject
import com.sun.jersey.api.core.PackagesResourceConfig
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.spi.inject.SingletonTypeInjectableProvider
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.nio.BlockingChannelConnector
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.slf4j.LoggerFactory
import org.midonet.brain.services.rest_api.render.ResourceRenderer
import org.midonet.brain.{BrainConfig, ClusterMinion, ClusterNode}
import org.midonet.cluster.services.MidonetBackend

object Vladimir {
    val rootUri = "/midonet-api"

    // TODO: unnecessary when we learn how to inject in Jersey
    var backend: MidonetBackend = _
    var resRenderer: ResourceRenderer = _
}

class Vladimir @Inject()(nodeContext: ClusterNode.Context,
                         backend: MidonetBackend,
                         config: BrainConfig)
    extends ClusterMinion(nodeContext) {

    private val log = LoggerFactory.getLogger("org.midonet.rest-api")

    var server: Server = _

    // TODO: unnecessary when we learn how to inject in Jersey
    Vladimir.backend = backend
    Vladimir.resRenderer = new ResourceRenderer(backend)

    log.info(s"Backend: ${backend.store} ${backend.store.isBuilt}")

    override def doStart(): Unit = {
        log.info(s"Starting REST API service at ${config.restApi.httpPort}")

        server = new Server()
        val http = new BlockingChannelConnector()
        http.setPort(config.restApi.httpPort)
        server.addConnector(http)

        val servletHolder = new ServletHolder(classOf[ServletContainer])
        // Order matters, make sure that CORS is first, as it's needed for
        // login too.
        servletHolder.setInitParameter("com.sun.jersey.spi.container.ContainerResponseFilters",
                                       classOf[CorsFilter].getName)
        servletHolder.setInitParameter("com.sun.jersey.spi.container.ContainerRequestFilters",
                                       "com.sun.jersey.api.container.filter.LoggingFilter")
        servletHolder.setInitParameter("com.sun.jersey.config.property.resourceConfigClass",
                                       classOf[PackagesResourceConfig].getName)
        servletHolder.setInitParameter("com.sun.jersey.config.property.packages",
                                       "org.midonet.brain.services.vladimir.api;org.codehaus.jackson.jaxrs.json")
        servletHolder.setInitParameter("com.sun.jersey.spi.container.ContainerResponseFilters",
                                       "com.sun.jersey.api.container.filter.LoggingFilter")
        servletHolder.setInitParameter("com.sun.jersey.api.json.POJOMappingFeature", "true")
        val context = new ServletContextHandler()
        context.setContextPath(Vladimir.rootUri)
        context.addServlet(servletHolder, "/*")
        context.setClassLoader(Thread.currentThread().getContextClassLoader)

        try {
            server.setHandler(context)
            server.start()
        } finally {
            notifyStarted()
        }

    }

    override def doStop(): Unit = {
        try {
            if (server ne null) {
                server.stop()
                server.join()
            }
        } finally {
            server.destroy()
        }
        notifyStopped()
    }

}

@Provider
class MidonetBackendProvider (@Inject() backend: MidonetBackend) extends
    SingletonTypeInjectableProvider[Context, MidonetBackend](classOf[MidonetBackend], backend)
