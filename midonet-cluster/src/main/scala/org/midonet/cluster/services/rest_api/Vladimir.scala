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

import java.io.File
import java.util

import javax.servlet.DispatcherType
import javax.validation.Validator

import scala.collection.JavaConversions._

import com.google.inject.Guice._
import com.google.inject.servlet.{GuiceFilter, GuiceServletContextListener}
import com.google.inject.{Inject, Injector}
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.eclipse.jetty.http.HttpVersion._
import org.eclipse.jetty.server._
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import org.midonet.cluster.restApiLog
import org.midonet.cluster.auth.{AuthModule, AuthService}
import org.midonet.cluster.data.storage.StateTableStorage
import org.midonet.cluster.rest_api.auth.{AdminOnlyAuthFilter, AuthFilter, LoginFilter}
import org.midonet.cluster.rest_api.jaxrs.WildcardJacksonJaxbJsonProvider
import org.midonet.cluster.rest_api.validation.ValidatorProvider
import org.midonet.cluster.services.c3po.{C3POMinion, C3POStorageManager}
import org.midonet.cluster.services.rest_api.resources._
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.storage.{LegacyStateTableStorage, MidonetBackendConfig}
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.conf.MidoNodeConfigurator
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

        val resProvider = new ResourceProvider(log)
        val sequenceDispenser = new SequenceDispenser(curator, config.backend)

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
            bind(classOf[SequenceDispenser]).toInstance(sequenceDispenser)
            bind(classOf[MidoNodeConfigurator])
                .toInstance(MidoNodeConfigurator(
                curator.usingNamespace(config.backend.rootKey.stripPrefix("/")),
                None))
            bind(classOf[C3POStorageManager])
                .toInstance(C3POMinion.initDataManager(backend.store,
                                                       sequenceDispenser,
                                                       paths))
            bind(classOf[ResourceProvider]).toInstance(resProvider)
            bind(classOf[ApplicationResource])
            bind(classOf[Validator])
                .toProvider(classOf[ValidatorProvider])
                .asEagerSingleton()

            filter("/*").through(classOf[CorsFilter])
            filter("/login").through(classOf[LoginFilter])
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
    private val log = Logger(LoggerFactory.getLogger(restApiLog))

    override def isEnabled = config.restApi.isEnabled

    override def doStart(): Unit = {
        log.info(s"Starting REST API service at ports: " +
                 s"HTTP: ${config.restApi.httpPort} HTTPS: " +
                 s"${config.restApi.httpsPort}, " +
                 s"with root uri: ${config.restApi.rootUri} " +
                 s"and auth service: ${authService.getClass.getName}")

        server = new Server()

        val http = httpConnector()
        val https = httpsConnector()

        server.setConnectors(if (https == null) Array(http)
                             else Array (http, https))

        try {
            server.setHandler(prepareContext())
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

    /** Prepare the ServletContextHandler that we'll use to serve requests.
      *
      * Here we will configure the Guice event listener that captures
      * all requests and sends them through the Guice handler.
      */
    private def prepareContext() = {
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
        context
    }

    /** Build a basic HTTP configuration. */
    private def sampleHttpConfig(): HttpConfiguration = {
        val c = new HttpConfiguration()
        c.setSecureScheme("https")
        c.setSecurePort(config.restApi.httpsPort)
        c.setOutputBufferSize(32768)
        c
    }

    /** Build and configure an HTTP connector. */
    private def httpConnector(): ServerConnector = {
        val cfg = sampleHttpConfig()
        val http = new ServerConnector(server, new HttpConnectionFactory(cfg))
        http.setPort(config.restApi.httpPort)
        http.setIdleTimeout(30000)
        http
    }

    /**
     * Builds and configures the HTTPS connector, based on the keystore path
     * specified at the system property midonet.keystore_path, or the default
     * location.
     *
     * @return the connector if an https file is available and the port
     *         specified in config is > 0, null otherwise
     */
    private def httpsConnector(): ServerConnector = {
        // Following
        // https://www.eclipse.org/jetty/documentation/current/configuring-ssl.html

        val keystoreFile = System.getProperty("midonet.keystore_path",
                                              "/etc/midonet-cluster/ssl/midonet.jks")

        if (config.restApi.httpsPort <= 0) {
            log.info(s"Https explicitly disabled (https port is <= 0)")
            return null
        }

        if (new File(keystoreFile).exists()) {
            log.info(s"Keystore file: $keystoreFile")
        } else {
            log.info(s"Keystore file not found: $keystoreFile - HTTPS will be" +
                     s" disabled")
            return null
        }

        val keystorePass = System.getProperty("midonet.keystore_password")
        val sslContextFactory = new SslContextFactory()
        sslContextFactory.setKeyStorePath(keystoreFile)
        sslContextFactory.setKeyStorePassword(keystorePass)
        sslContextFactory.setKeyManagerPassword(keystorePass)

        val sslCnxnFactory = new SslConnectionFactory(sslContextFactory,
                                                      HTTP_1_1.asString())
        val httpsConfig = sampleHttpConfig()
        httpsConfig.addCustomizer(new SecureRequestCustomizer())
        val cnxnFactory = new HttpConnectionFactory(httpsConfig)
        val https = new ServerConnector(server, sslCnxnFactory, cnxnFactory)
        https.setPort(config.restApi.httpsPort)
        https.setIdleTimeout(500000)
        https
    }

}

