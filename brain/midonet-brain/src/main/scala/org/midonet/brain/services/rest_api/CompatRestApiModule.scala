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
import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest

import scala.collection.JavaConversions._

import com.google.inject.assistedinject.FactoryModuleBuilder
import com.sun.jersey.api.container.filter.{LoggingFilter, RolesAllowedResourceFilterFactory}
import com.sun.jersey.api.core.ResourceConfig.{PROPERTY_CONTAINER_REQUEST_FILTERS, PROPERTY_CONTAINER_RESPONSE_FILTERS, PROPERTY_RESOURCE_FILTER_FACTORIES}
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import org.apache.commons.lang3.StringUtils.join
import org.slf4j.LoggerFactory.getLogger

import org.midonet.brain.services.rest_api.auth._
import org.midonet.brain.services.rest_api.auth.cors.CrossOriginResourceSharingFilter
import org.midonet.brain.services.rest_api.error.{ErrorModule, ExceptionFilter}
import org.midonet.brain.services.rest_api.resources.ApplicationResource
import org.midonet.brain.services.rest_api.rest_api.WebApplicationExceptionMapper
import org.midonet.brain.services.rest_api.validation.ValidationModule
import org.midonet.brain.services.vladimir.StateFilter
import org.midonet.brain.{BrainConfig, ClusterNode}
import org.midonet.config.ConfigProvider
import org.midonet.config.providers.ServletContextConfigProvider

class CompatRestApiModule(clusterContext: ClusterNode.Context,
                          brainConfig: BrainConfig,
                          servletContext: ServletContext)
    extends JerseyServletModule {

    private val log = getLogger(classOf[CompatRestApiModule])

    private val reqFilters = Array (
        classOf[LoggingFilter].getCanonicalName,
        classOf[AuthContainerRequestFilter].getCanonicalName
    )
    private val resFilters = Array (
        classOf[ExceptionFilter].getCanonicalName,
        classOf[LoggingFilter].getCanonicalName
    )
    protected final val servletParams = Map (
        PROPERTY_CONTAINER_REQUEST_FILTERS -> join(reqFilters, ";"),
        PROPERTY_CONTAINER_RESPONSE_FILTERS -> join(resFilters, ";"),
        PROPERTY_RESOURCE_FILTER_FACTORIES -> classOf[RolesAllowedResourceFilterFactory].getCanonicalName
    )

    override protected def configureServlets() {

        log.debug("configureServlets: entered")

        // TODO: get rid of this, just used for auth, see with:
        //       git grep ConfigProvider brain/midonet-brain/
        bind(classOf[ConfigProvider])
            .toInstance(new ServletContextConfigProvider(servletContext))

        // Pass through the given BrainConfig
        bind(classOf[BrainConfig]).toInstance(brainConfig)

        // Handle errors gracefully
        bind(classOf[WebApplicationExceptionMapper]).asEagerSingleton()

        // The top level resource, that describes our app
        bind(classOf[ApplicationResource])

        // This will figure out what Resource classes exist to serve HTTP reqs.
        // All reside at org.midonet.brain.services.rest_api.resources.*
        // TODO: consider replacing with some parameter that actually auto
        //       discovers them in a path
        install(new FactoryModuleBuilder().build(classOf[ResourceFactory]))

        install(new AbstractAuthModule() {
            // TODO: do these
            override def bindAuthServices(): Unit = {
                bind(classOf[AuthService]).toInstance(new AuthService {
                    val mockTenant = new MockTenant("mocktenant")
                    override def getTenants(request: HttpServletRequest):
                    util.List[Tenant] = List(mockTenant)
                    override def getUserIdentityByToken(token: String): UserIdentity =
                        new UserIdentity
                    override def getTenant(id: String): Tenant = mockTenant
                    override def login(username: String, password: String,
                                       request: HttpServletRequest): Token = new Token()
                })
            }
            override def bindAuthorizers(): Unit = {}
        })
        install(new ErrorModule())
        install(new ValidationModule())

        log.error("Neutron modules still not implemented!! LAZY SLACKER!")
        // TODO: replace these two with ZOOM based alternatives
        // install(new NeutronClusterApiModule())
        // install(new NeutronRestApiModule())
        // install(new NetworkModule())

        // Register filters - the order matters here.  Make sure that CORS
        // filter is registered first because Auth would reject OPTION
        // requests without a token in the header. The Login filter relies
        // on CORS as well.
        filter("/*").through(classOf[CrossOriginResourceSharingFilter])
        filter("/login").through(classOf[LoginFilter])
        filter("/*").through(classOf[AuthFilter])
        filter("/*").through(classOf[StateFilter])

        // Register servlet
        serve("/*").`with`(classOf[GuiceContainer], servletParams)

        log.debug("configureServlets: exiting")
    }

}

