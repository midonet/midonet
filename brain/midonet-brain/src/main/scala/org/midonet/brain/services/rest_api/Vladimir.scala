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

import java.util

import com.google.inject.Inject
import com.google.inject.servlet.GuiceFilter
import org.eclipse.jetty.server.nio.BlockingChannelConnector
import org.eclipse.jetty.server.{DispatcherType, Server}
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.slf4j.LoggerFactory

import org.midonet.brain.{BrainConfig, ClusterMinion, ClusterNode}
import org.midonet.cluster.services.MidonetBackend

object Vladimir {

    val rootUri = "/midonet-api"
    var backend: MidonetBackend = _

}

class Vladimir @Inject()(nodeContext: ClusterNode.Context,
                         brainConfig: BrainConfig,
                         backend: MidonetBackend,
                         config: BrainConfig)
    extends ClusterMinion(nodeContext) {

    private val log = LoggerFactory.getLogger("org.midonet.rest-api")

    var server: Server = _

    // TODO: unnecessary when we learn how to inject in Jersey
    Vladimir.backend = backend
    log.info(s"Backend: ${backend.store} ${backend.store.isBuilt}")

    override def doStart(): Unit = {
        log.info(s"Starting REST API service at ${config.restApi.httpPort}")

        server = new Server()
        val http = new BlockingChannelConnector()
        http.setPort(config.restApi.httpPort)
        server.addConnector(http)

        // TODO: the old api does this at RestApiJerseyServeltModule
        val context = new ServletContextHandler()
        context.setContextPath(Vladimir.rootUri)
        //  Jetty requires some servlet to be bound to the path, otherwise
        // request is just skipped. This prevents Guice from handling the req.
        context.addServlet(classOf[DefaultServlet], "/")
        context.addEventListener(new CompatRestApiJerseyContextListener
                                     (nodeContext, brainConfig))
        context.addFilter(classOf[GuiceFilter], "/*",
                          util.EnumSet.allOf (classOf[DispatcherType]))

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
