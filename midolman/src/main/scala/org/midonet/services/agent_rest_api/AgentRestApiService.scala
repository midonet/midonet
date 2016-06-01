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

package org.midonet.services.agent_rest_api

import java.util.EnumSet
import java.util.concurrent.ExecutorService

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import javax.servlet.DispatcherType

import org.apache.curator.framework.CuratorFramework
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Server;
import org.slf4j.LoggerFactory

import com.google.inject.Guice
import com.google.inject.Inject
import com.google.inject.name.Named
import com.google.inject.servlet.GuiceFilter
import com.google.inject.servlet.GuiceServletContextListener
import com.sun.jersey.spi.container.servlet.ServletContainer
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import com.typesafe.scalalogging.Logger

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.PathBuilder
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.services.AgentRestApiLog
import org.midonet.services.agent_rest_api.hacks.UnixDomainServerConnector

object AgentRestApiService {
    val Log = Logger(LoggerFactory.getLogger(AgentRestApiLog))
}

/**
  * This is the cluster service to provide rest api over unix domain socket
  * for agent.
  */
@MinionService(name = "agent-rest-api", runsOn = TargetNode.AGENT)
class AgentRestApiService @Inject()(
        nodeContext: Context,
        backend: MidonetBackend,
        curator: CuratorFramework,
        @Named("agent-services-pool") executor: ExecutorService,
        config: MidolmanConfig)
    extends Minion(nodeContext) {

    import AgentRestApiService.Log

    var server: Server = null

    override def isEnabled: Boolean = config.agentRestApi.isEnabled

    private def makeModule = {
        new JerseyServletModule {
            override protected def configureServlets = {
                bind(classOf[CuratorFramework]).toInstance(curator)
                bind(classOf[MidolmanConfig]).toInstance(config)
                bind(classOf[MidonetBackend]).toInstance(backend)
                bind(classOf[PathBuilder]).asEagerSingleton
                bind(classOf[ZookeeperLockFactory]).asEagerSingleton
                bind(classOf[BindingHandler]).asEagerSingleton
                serve("/*").`with`(classOf[GuiceContainer])
            }
        }
    }

    private def makeServer = {
        val server = new Server()
        val connector = new UnixDomainServerConnector(server, executor,
            null, null, -1, -1, new HttpConnectionFactory())
        connector.setUnixSocket(config.agentRestApi.unixSocket)
        val connectors = Array[Connector](connector)
        server.setConnectors(connectors)
        val context = new ServletContextHandler(ServletContextHandler.SESSIONS)
        context.addEventListener(new GuiceServletContextListener {
            override protected def getInjector = Guice.createInjector(makeModule)
        })
        context.addFilter(classOf[GuiceFilter], "/*",
                          EnumSet.allOf(classOf[DispatcherType]))
        context.addServlet(classOf[DefaultServlet], "/*")
        context.setClassLoader(Thread.currentThread().getContextClassLoader)
        server.setHandler(context)
        server
    }

    protected override def doStart(): Unit = {
        this.synchronized {
            Log info "Starting agent-rest-api service"

            try {
                val s = makeServer
                s.start
                server = s
                notifyStarted()
            } catch {
                case NonFatal(e) =>
                    Log error "Failed to start agent-rest-api service"
                    notifyFailed(e)
            }
        }
    }

    protected override def doStop(): Unit = {
        this.synchronized {
            Log info "Stopping agent-rest-api service"

            if (server != null) {
                server.stop
                server.join
                server = null
            }
            notifyStopped()
        }
    }
}
