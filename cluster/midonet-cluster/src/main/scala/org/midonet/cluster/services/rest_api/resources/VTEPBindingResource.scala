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

import java.util.{List => JList, UUID}

import javax.ws.rs.core.Response.Status
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.ApiException
import org.midonet.cluster.rest_api.annotation.AllowCreate
import org.midonet.cluster.rest_api.models.{Bridge, VTEP, VTEPBinding}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowCreate(Array(APPLICATION_VTEP_BINDING_JSON,
                   APPLICATION_JSON))
class VTEPBindingResource @Inject()(mgmtIp: String, resContext: ResourceContext)
    extends MidonetResource[VTEPBinding](resContext) {

    @GET
    @Path("{portName}/{vlanId}")
    @Produces(Array(APPLICATION_VTEP_BINDING_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("portName") portName: String,
            @PathParam("vlanId") vlanId: Short): VTEPBinding = {

        getVtep.flatMap(vtep => {
            listResources(classOf[VTEPBinding], vtep.bindings.asScala)
                .map(_.find(binding =>
                                binding.portName == portName &&
                                binding.vlanId == vlanId))
        })
            .getOrThrow
            .getOrElse(throw new ApiException(Status.NOT_FOUND,
                                              getMessage(VTEP_BINDING_NOT_FOUND)))
    }

    @GET
    @Produces(Array(APPLICATION_VTEP_BINDING_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[VTEPBinding] = {
        getVtep.flatMap(vtep => {
            listResources(classOf[VTEPBinding], vtep.bindings.asScala)
        })
            .getOrThrow
            .asJava
    }

    protected override def createFilter = (binding: VTEPBinding) => {
        hasResource(classOf[Bridge], binding.networkId).map(exists => {
            // Validate the bridge exists.
            if (!exists) throw new ApiException(Status.NOT_FOUND)
        }).getOrThrow
        binding.create(mgmtIp)
    }

    private def getVtep: Future[VTEP] = {
        listResources(classOf[VTEP])
            .map(_.find(_.managementIp == mgmtIp)
                  .getOrElse(throw new ApiException(Status.NOT_FOUND,
                                                    getMessage(VTEP_NOT_FOUND))))
    }

}
