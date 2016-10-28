/*
 * Copyright 2016 Midokura SARL
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

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.google.inject.Guice._
import com.google.inject.servlet.{GuiceFilter, GuiceServletContextListener}
import com.google.inject.{Inject, Injector}
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import com.typesafe.scalalogging.Logger

import org.apache.commons.lang.StringUtils
import org.apache.curator.framework.CuratorFramework
import org.eclipse.jetty.http.HttpVersion._
import org.eclipse.jetty.server._
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.util.thread.{QueuedThreadPool, ScheduledExecutorScheduler}
import org.reflections.Reflections
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import org.midonet.cluster.auth.{AuthModule, AuthService}
import org.midonet.cluster.rest_api.auth.{AdminOnlyAuthFilter, AuthFilter, LoginFilter}
import org.midonet.cluster.rest_api.jaxrs.WildcardJacksonJaxbJsonProvider
import org.midonet.cluster.rest_api.validation.ValidatorProvider
import org.midonet.cluster.services.c3po.NeutronTranslatorManager
import org.midonet.cluster.services.rest_api.resources._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.{ClusterConfig, RestApiConfig, RestApiLog}
import org.midonet.conf.MidoNodeConfigurator
import org.midonet.midolman.state.PathBuilder
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}

object RestApi {

    final val ContainerRequestFiltersClass =
        "com.sun.jersey.spi.container.ContainerRequestFilters"
    final val ContainerResponseFiltersClass =
        "com.sun.jersey.spi.container.ContainerResponseFilters"
    final val LoggingFilterClass =
        "com.sun.jersey.api.container.filter.LoggingFilter"
    final val GzipFilterClass =
        "com.sun.jersey.api.container.filter.GZIPContentEncodingFilter"
    final val PojoMappingFeatureClass =
        "com.sun.jersey.api.json.POJOMappingFeature"

    def servletModule(backend: MidonetBackend, ec: ExecutionContext,
                      curator: CuratorFramework, config: ClusterConfig,
                      reflections: Reflections, authService: AuthService,
                      log: Logger) = new JerseyServletModule {

        val resProvider = new ResourceProvider(reflections, log)
        val sequenceDispenser = new SequenceDispenser(curator, config.backend)

        override def configureServlets(): Unit = {
            // To redirect JDK log to slf4j. Ref: MNA-706
            SLF4JBridgeHandler.removeHandlersForRootLogger()
            SLF4JBridgeHandler.install()

            install(new AuthModule(config.auth, log))

            val paths = new PathBuilder(config.backend.rootKey)

            bind(classOf[WildcardJacksonJaxbJsonProvider]).asEagerSingleton()
            bind(classOf[CorsFilter])
            bind(classOf[RestApiConfig]).toInstance(config.restApi)
            bind(classOf[PathBuilder]).toInstance(paths)
            bind(classOf[ExecutionContext]).toInstance(ec)
            bind(classOf[CuratorFramework]).toInstance(curator)
            bind(classOf[MidonetBackend]).toInstance(backend)
            bind(classOf[MidonetBackendConfig]).toInstance(config.backend)
            bind(classOf[SequenceDispenser]).toInstance(sequenceDispenser)
            bind(classOf[MidoNodeConfigurator])
                .toInstance(MidoNodeConfigurator(
                curator.usingNamespace(config.backend.rootKey.stripPrefix("/")),
                None))
            bind(classOf[NeutronTranslatorManager])
                .toInstance(new NeutronTranslatorManager(config, backend,
                                                         sequenceDispenser))
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
                ContainerRequestFiltersClass ->
                    s"$GzipFilterClass;$LoggingFilterClass",
                ContainerResponseFiltersClass ->
                    s"$GzipFilterClass;$LoggingFilterClass",
                PojoMappingFeatureClass -> "true"
            )
            serve("/*").`with`(classOf[GuiceContainer], initParams.asJava)
        }
    }
}

@MinionService(name = "rest-api", runsOn = TargetNode.CLUSTER)
class RestApi @Inject()(nodeContext: Context,
                        backend: MidonetBackend,
                        curator: CuratorFramework,
                        reflections: Reflections,
                        authService: AuthService,
                        config: ClusterConfig)
    extends Minion(nodeContext) {

    import RestApi._

    private var server: Server = _
    private val log = Logger(LoggerFactory.getLogger(RestApiLog))
    private val executor = createThreadPool()
    private val executionContext = ExecutionContext.fromExecutor(executor)

    override def isEnabled = config.restApi.isEnabled

    override def doStart(): Unit = {
        log.info("Starting REST API service " +
                 s"HTTP: ${config.restApi.httpHost}:${config.restApi.httpPort} " +
                 s"HTTPS: ${config.restApi.httpsHost}:${config.restApi.httpsPort} " +
                 s"root URI: ${config.restApi.rootUri}")

        server = new Server(executor)
        server.addBean(new ScheduledExecutorScheduler("rest-api-scheduler", false))

        val http = httpConnector()
        val https = httpsConnector()

        server.setConnectors(if (https == null) Array(http)
                             else Array(http, https))

        try {
            server.setHandler(prepareContext())
            server.start()
            notifyStarted()
        } catch {
            case NonFatal(e) => notifyFailed(e)
        }
    }

    override def doStop(): Unit = {
        try {
            if (server ne null) {
                server.stop()
            }
            executor.stop()
            if (server ne null) {
                server.join()
            }
        } finally {
            if (server ne null) {
                server.destroy()
            }
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
                createInjector(servletModule(backend, executionContext, curator,
                                             config, reflections, authService, log))
            }
        })
        val allDispatchers = util.EnumSet.allOf(classOf[DispatcherType])
        context.addFilter(classOf[GuiceFilter], "/*", allDispatchers)
        context.addServlet(classOf[DefaultServlet], "/*")
        context.setClassLoader(Thread.currentThread().getContextClassLoader)
        context
    }

    /**
      * @return The thread pool for the Jetty server.
      */
    private def createThreadPool(): QueuedThreadPool = {
        val threadPool =
            new QueuedThreadPool(config.restApi.maxThreadPoolSize,
                                 config.restApi.minThreadPoolSize,
                                 config.restApi.threadPoolIdleTimeoutMs.toInt)

        threadPool.setName("rest-api")
        threadPool
    }

    /** Build a basic HTTP configuration. */
    private def createHttpConfig(): HttpConfiguration = {
        val httpConfig = new HttpConfiguration()
        httpConfig.setSecureScheme("https")
        httpConfig.setSecurePort(config.restApi.httpsPort)
        httpConfig.addCustomizer(new ForwardedRequestCustomizer())
        httpConfig.setOutputBufferSize(config.restApi.outputBufferSize)
        httpConfig
    }

    /** Build and configure an HTTP connector. */
    private def httpConnector(): ServerConnector = {
        val http = new ServerConnector(
            server,
            config.restApi.acceptorThreads,
            config.restApi.selectorThreads,
            new HttpConnectionFactory(createHttpConfig()))
        http.setHost(StringUtils.defaultIfBlank(config.restApi.httpHost, null))
        http.setPort(config.restApi.httpPort)
        http.setIdleTimeout(config.restApi.httpIdleTimeoutMs)
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

        val keystoreFile =
            System.getProperty("midonet.keystore_path",
                               "/etc/midonet-cluster/ssl/midonet.jks")

        if (config.restApi.httpsPort <= 0) {
            log.info("HTTPS explicitly disabled: port is not positive")
            return null
        }

        if (new File(keystoreFile).exists()) {
            log.info(s"Keystore file: $keystoreFile")
        } else {
            log.info(s"Keystore file not found: $keystoreFile HTTPS will be " +
                     "disabled")
            return null
        }

        val keystorePass = System.getProperty("midonet.keystore_password")
        val sslContextFactory = new SslContextFactory()
        sslContextFactory.setKeyStorePath(keystoreFile)
        sslContextFactory.setKeyStorePassword(keystorePass)
        sslContextFactory.setKeyManagerPassword(keystorePass)

        val httpsConfig = createHttpConfig()
        val httpConnectionFactory =
            new HttpConnectionFactory(httpsConfig)
        val sslConnectionFactory =
            new SslConnectionFactory(sslContextFactory, HTTP_1_1.asString())
        httpsConfig.addCustomizer(new SecureRequestCustomizer())
        val https = new ServerConnector(
            server,
            config.restApi.acceptorThreads,
            config.restApi.selectorThreads,
            sslConnectionFactory, httpConnectionFactory)
        https.setHost(StringUtils.defaultIfBlank(config.restApi.httpsHost, null))
        https.setPort(config.restApi.httpsPort)
        https.setIdleTimeout(config.restApi.httpsIdleTimeoutMs)
        https
    }



}

