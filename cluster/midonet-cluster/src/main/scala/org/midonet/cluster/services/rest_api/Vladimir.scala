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
import javax.validation.Validator

import scala.collection.JavaConversions._

import com.google.inject.Guice._
import com.google.inject.servlet.{GuiceFilter, GuiceServletContextListener}
import com.google.inject.{Inject, Injector}
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import com.typesafe.scalalogging.Logger
import org.apache.curator.framework.CuratorFramework
import org.eclipse.jetty.server.{DispatcherType, Server}
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.slf4j.bridge.SLF4JBridgeHandler
import org.slf4j.LoggerFactory

import org.midonet.cluster.auth.{AuthModule, AuthService}
import org.midonet.cluster.data.storage.StateTableStorage
import org.midonet.cluster.rest_api.auth.{AdminOnlyAuthFilter, AuthFilter}
import org.midonet.cluster.rest_api.jaxrs.WildcardJacksonJaxbJsonProvider
import org.midonet.cluster.rest_api.validation.ValidatorProvider
import org.midonet.cluster.services.rest_api.resources._
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.storage.{LegacyStateTableStorage, MidonetBackendConfig}
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.midolman.state.PathBuilder

object Vladimir {

    final val ContainerResponseFiltersClass =
        "com.sun.jersey.spi.container.ContainerResponseFilters"
    final val ContainerRequestFiltersClass =
        "com.sun.jersey.spi.container.ContainerRequestFilters"
    final val LoggingFilterClass =
        "com.sun.jersey.api.container.filter.LoggingFilter"
    final val POJOMappingFeatureClass =
        "com.sun.jersey.api.json.POJOMappingFeature"

    def servletModule(backend: MidonetBackend, curator: CuratorFramework,
                      config: ClusterConfig, authService: AuthService,
                      log: Logger) = new JerseyServletModule {

        override def configureServlets(): Unit = {
            // To redirect JDK log to slf4j. Ref: MNA-706
            SLF4JBridgeHandler.removeHandlersForRootLogger()
            SLF4JBridgeHandler.install()

            install(new AuthModule(config.auth, log))

            val paths = new PathBuilder(config.backend.rootKey)

            bind(classOf[WildcardJacksonJaxbJsonProvider]).asEagerSingleton()
            bind(classOf[CorsFilter])
            bind(classOf[PathBuilder]).toInstance(paths)
            bind(classOf[StateTableStorage])
                .toInstance(new LegacyStateTableStorage(curator, paths))
            bind(classOf[CuratorFramework]).toInstance(curator)
            bind(classOf[MidonetBackend]).toInstance(backend)
            bind(classOf[MidonetBackendConfig]).toInstance(config.backend)
            bind(classOf[SequenceDispenser]).asEagerSingleton()
            bind(classOf[ApplicationResource])
            bind(classOf[Validator])
                .toProvider(classOf[ValidatorProvider])
                .asEagerSingleton()

            filter("/*").through(classOf[CorsFilter])
            filter("/*").through(classOf[AuthFilter])
            filter("/*").through(classOf[AdminOnlyAuthFilter])

            val initParams = Map(
                ContainerRequestFiltersClass -> LoggingFilterClass,
                ContainerResponseFiltersClass -> LoggingFilterClass,
                POJOMappingFeatureClass -> "true"
            )
            serve("/*").`with`(classOf[GuiceContainer], initParams)
        }
    }
}

@ClusterService(name = "rest_api")
class Vladimir @Inject()(nodeContext: ClusterNode.Context,
                         backend: MidonetBackend,
                         curator: CuratorFramework,
                         authService: AuthService,
                         config: ClusterConfig)
    extends Minion(nodeContext) {

    import Vladimir._

    private var server: Server = _
    private val log = Logger(LoggerFactory.getLogger("org.midonet.rest-api"))

    override def isEnabled = config.restApi.isEnabled

    override def doStart(): Unit = {
        log.info(s"Starting REST API service at port: " +
                 s"${config.restApi.httpPort}, with " +
                 s"root uri: ${config.restApi.rootUri} " +
                 s"and auth service: ${authService.getClass.getName}")

        server = new Server(config.restApi.httpPort)

        val context = new ServletContextHandler(server, config.restApi.rootUri,
                                                ServletContextHandler.SESSIONS)
        context.addEventListener(new GuiceServletContextListener {
            override def getInjector: Injector = {
                createInjector(servletModule(backend, curator, config,
                                             authService, log))
            }
        })

        val allDispatchers = util.EnumSet.allOf(classOf[DispatcherType])
        context.addFilter(classOf[GuiceFilter], "/*", allDispatchers)
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

