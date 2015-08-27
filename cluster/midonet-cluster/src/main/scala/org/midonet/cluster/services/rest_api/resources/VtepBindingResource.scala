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

import java.net.URLEncoder
import java.util.function.Predicate
import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{Bridge, Vtep, VtepBinding}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException, ServiceUnavailableHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkNoContentResponse, OkCreated, ResourceContext}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbVtepDataClient

@RequestScoped
class VtepBindingResource @Inject()(vtepId: UUID, resContext: ResourceContext)
    extends MidonetResource[VtepBinding](resContext) {

    @GET
    @Path("{portName}/{vlanId}")
    @Produces(Array(APPLICATION_VTEP_BINDING_JSON,
                    APPLICATION_JSON))
    def get(@PathParam("portName") portName: String,
            @PathParam("vlanId") vlanId: Short): VtepBinding = {

        getResource(classOf[Vtep], vtepId) map { vtep =>
            val binding = vtep.bindings.asScala
                .find(binding =>
                          binding.portName == portName &&
                          binding.vlanId == vlanId)
                .getOrElse(throw new NotFoundHttpException(
                    getMessage(VTEP_BINDING_NOT_FOUND)))
            binding.setBaseUri(resContext.uriInfo.getBaseUri)
            binding.vtepId = vtepId
            binding
        } getOrThrow
    }

    @GET
    @Produces(Array(APPLICATION_VTEP_BINDING_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[VtepBinding] = {
        getResource(classOf[Vtep], vtepId) map {
            _.bindings.asScala
                      .map(binding => {
                          binding.setBaseUri(resContext.uriInfo.getBaseUri)
                          binding.vtepId = vtepId
                          binding
                      })
                      .asJava
        } getOrThrow
    }

    @POST
    @Consumes(Array(APPLICATION_VTEP_BINDING_JSON,
                    APPLICATION_JSON))
    override def create(binding: VtepBinding,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        throwIfViolationsOn(binding)

        // Validate the port name.
        if (URLEncoder.encode(binding.portName, "UTF-8") != binding.portName) {
            throw new BadRequestHttpException(
                s"Port name ${binding.portName} contains invalid URI characters")
        }

        // Validate the bridge exists.
        hasResource(classOf[Bridge], binding.networkId) map { exists =>
            if (!exists) throw new BadRequestHttpException(
                s"Bridge ${binding.networkId} not found")
        } getOrThrow

        // Validate the physical port exists.
        getResource(classOf[Vtep], vtepId) map { vtep =>
            val client = OvsdbVtepDataClient(IPv4Addr(vtep.managementIp),
                                             vtep.managementPort)
            try {
                client.connect() flatMap { _ =>
                    client.physicalSwitch
                } flatMap {
                    case Some(physicalSwitch) =>
                        val futures = for(portId <- physicalSwitch.ports) yield
                            client.physicalPort(portId)
                        Future.sequence(futures)
                    case None =>
                        throw new ServiceUnavailableHttpException(
                            s"Cannot add binding to VTEP ${vtep.managementIp}:" +
                            s"${vtep.managementPort} because the physical " +
                            "switch is not configured")
                } map { ports =>
                    if (!ports.flatten.exists(_.name == binding.portName)) {
                        throw new ServiceUnavailableHttpException(
                            s"Cannot add binding to VTEP ${vtep.managementIp}:" +
                            s"${vtep.managementPort} because the physical " +
                            s"port ${binding.portName} does not exist")
                    }
                    binding.setBaseUri(resContext.uriInfo.getBaseUri)
                    binding.vtepId = vtepId
                    vtep.bindings.add(binding)
                    updateResource(vtep, OkCreated(binding.getUri))
                } getOrThrow
            } finally {
                client.close()
            }
        } getOrThrow
    }

    @DELETE
    @Path("{portName}/{vlanId}")
    def delete(@PathParam("portName") portName: String,
               @PathParam("vlanId") vlanId: Short): Response = {
        // Validate the physical port exists.
        getResource(classOf[Vtep], vtepId) map { vtep =>
            if (vtep.bindings.removeIf(new Predicate[VtepBinding] {
                override def test(binding: VtepBinding): Boolean = {
                    binding.portName == portName && binding.vlanId == vlanId
                }
            })) {
                updateResource(vtep, OkNoContentResponse)
            } else {
                throw new NotFoundHttpException(
                    getMessage(VTEP_BINDING_NOT_FOUND, vtepId,
                               Short.box(vlanId), portName))
            }
        } getOrThrow
    }

}
