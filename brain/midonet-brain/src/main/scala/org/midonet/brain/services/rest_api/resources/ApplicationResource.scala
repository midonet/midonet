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

package org.midonet.brain.services.rest_api.resources

import java.net.URI
import javax.annotation.security.PermitAll
import javax.ws.rs.core.{MediaType, SecurityContext, UriBuilder, UriInfo}
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger

import org.midonet.brain.BrainConfig
import org.midonet.brain.services.rest_api.version.Version
import org.midonet.brain.services.rest_api.{Application, ResourceFactory, ResourceUriBuilder, VendorMediaType}

@RequestScoped
@Path(ResourceUriBuilder.ROOT)
class ApplicationResource @Inject() (conf: BrainConfig,
                                     uriInfo: UriInfo,
                                     context: SecurityContext,
                                     factory: ResourceFactory) {

    private val log: Logger = getLogger(classOf[ApplicationResource])

    /**
     * @return The URI specified in the configuration file.  If not set, then
     * the base URI from the current request is returned.
     */
    def baseUri: URI = UriBuilder.fromPath(conf.restApi.baseUri).build()

    @GET
    @PermitAll
    @Produces(Array(VendorMediaType.APPLICATION_JSON_V4,
                    VendorMediaType.APPLICATION_JSON_V5,
                    MediaType.APPLICATION_JSON)) def get: Application = {
        val a: Application = new Application(baseUri)
        a.setVersion(Version.CURRENT)
        a
    }
}