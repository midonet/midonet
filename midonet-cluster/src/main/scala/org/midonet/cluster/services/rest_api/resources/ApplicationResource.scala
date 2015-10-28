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

package org.midonet.cluster.services.rest_api.resources

import javax.ws.rs.core.MediaType
import javax.ws.rs.{PathParam, GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Application
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.ResourceProvider
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@Path("/")
class ApplicationResource @Inject()(resProvider: ResourceProvider,
                                    resContext: ResourceContext)
    extends MidonetResource(resContext) {

    @GET
    @Produces(Array(MediaType.APPLICATION_JSON, APPLICATION_JSON_V5))
    def application: Application = {
        log.debug(s"${getClass.getName} entered on " +
                  s"${resContext.uriInfo.getAbsolutePath}")
        new Application(resProvider, resContext)
    }

    @Path("{name}/")
    def resource(@PathParam("name")name: String): Class[_] = {
        resProvider get name
    }
}
