/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import javax.servlet.{ServletContext, ServletContextEvent}

import com.google.inject.servlet.GuiceServletContextListener
import com.google.inject.{Guice, Injector}
import org.slf4j.LoggerFactory

import org.midonet.brain.{BrainConfig, ClusterNode}
import org.midonet.brain.southbound.vtep.VtepDataClientFactory

class CompatRestApiJerseyContextListener(nodeCtx: ClusterNode.Context,
                                         brainConfig: BrainConfig)
    extends GuiceServletContextListener {
    final val log = LoggerFactory.getLogger(classOf[CompatRestApiJerseyContextListener])

    private var servletContext: ServletContext = _
    private var injector: Injector = _

    override def contextInitialized(sce: ServletContextEvent) {
        log.debug("contextInitialized: entered")
        servletContext = sce.getServletContext
        super.contextInitialized(sce)
        log.debug("contextInitialized exiting")
    }

    protected def destroyApplication() {
        log.debug("destroyApplication: entered")
        injector.getInstance(classOf[VtepDataClientFactory])
                .dispose()
        log.debug("destroyApplication: exiting")
    }

    override def getInjector: Injector = {
        log.debug("getInjector: entered.")

        injector = Guice.createInjector(
            new CompatRestApiModule(nodeCtx, brainConfig, servletContext)
        )

        log.debug("getInjector: exiting.")
        injector
    }

}
