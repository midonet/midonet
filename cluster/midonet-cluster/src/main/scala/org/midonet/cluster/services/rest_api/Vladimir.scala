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

package org.midonet.cluster.services.rest_api

import java.util
import javax.ws.rs.core.MediaType
import javax.ws.rs.ext.Provider
import javax.ws.rs.{Consumes, Produces}

import com.google.inject.servlet.{GuiceFilter, GuiceServletContextListener}
import com.google.inject.{Guice, Inject, Injector}
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider
import org.codehaus.jackson.map.DeserializationConfig.Feature._
import org.codehaus.jackson.map.ObjectMapper
import org.eclipse.jetty.server.{DispatcherType, Server}
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.slf4j.LoggerFactory

import org.midonet.cluster.rest_api.neutron.resources.NeutronResource
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.resources._
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.{ClusterConfig, ClusterMinion, ClusterNode}

object Vladimir {

    final val RootUri = "/midonet-api"
    final val ContainerResponseFiltersClass =
        "com.sun.jersey.spi.container.ContainerResponseFilters"
    final val ContainerRequestFiltersClass =
        "com.sun.jersey.spi.container.ContainerRequestFilters"
    final val LoggingFilterClass =
        "com.sun.jersey.api.container.filter.LoggingFilter"
    final val POJOMappingFeatureClass =
        "com.sun.jersey.api.json.POJOMappingFeature"

    @Provider
    @Consumes(Array(MediaType.WILDCARD))
    @Produces(Array(MediaType.WILDCARD))
    class WildcardJacksonJaxbJsonProvider extends JacksonJaxbJsonProvider {

        val mapper = new ObjectMapper()
        mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
        mapper.configure(USE_GETTERS_AS_SETTERS, false)
        configure(FAIL_ON_UNKNOWN_PROPERTIES, false)

        override def locateMapper(`type`: Class[_], mediaType: MediaType)
        :ObjectMapper = {
            mapper
        }
    }
}

class Vladimir @Inject()(nodeContext: ClusterNode.Context,
                         backend: MidonetBackend,
                         config: ClusterConfig)
    extends ClusterMinion(nodeContext) {

    import Vladimir._

    private val log = LoggerFactory.getLogger("org.midonet.rest-api")

    private var server: Server = _

    def servletModule = new JerseyServletModule {
        override def configureServlets(): Unit = {
            bind(classOf[WildcardJacksonJaxbJsonProvider]).asEagerSingleton()
            bind(classOf[MidonetBackend]).toInstance(backend)
            bind(classOf[MidonetBackendConfig]).toInstance(config.backend)
            bind(classOf[ApplicationResource])
            bind(classOf[NeutronResource])
            val initParams = new java.util.HashMap[String, String]
            initParams.put(ContainerResponseFiltersClass,
                           classOf[CorsFilter].getName)
            initParams.put(ContainerRequestFiltersClass,
                           LoggingFilterClass)
            initParams.put(ContainerResponseFiltersClass,
                           LoggingFilterClass)
            initParams.put(POJOMappingFeatureClass, "true")
            serve("/*").`with`(classOf[GuiceContainer], initParams)
        }
    }

    override def doStart(): Unit = {
        log.info(s"Starting REST API service at ${config.restApi.httpPort}")

        server = new Server(config.restApi.httpPort)

        val context = new ServletContextHandler(server, RootUri,
                                                ServletContextHandler.SESSIONS)
        context.addEventListener(new GuiceServletContextListener {
            override def getInjector: Injector = {
                Guice.createInjector(servletModule)
            }
        })
        context.addFilter(classOf[GuiceFilter], "/*",
                          util.EnumSet.allOf(classOf[DispatcherType]))
        context.addServlet(classOf[DefaultServlet], "/*")
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

