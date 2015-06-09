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
import javax.ws.rs.core.UriInfo

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.TextFormat

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.State
import org.midonet.cluster.rest_api.models.{HostState, Host, Interface}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class InterfaceResource @Inject()(hostId: UUID, backend: MidonetBackend,
                                  uriInfo: UriInfo)
    extends MidonetResource[Interface](backend, uriInfo) {

    @GET
    @Path("{name}")
    @Produces(Array(APPLICATION_INTERFACE_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("name") name: String,
                     @HeaderParam("Accept") accept: String): Interface = {
        getInterfaces(hostId.toString)
            .map(_.find(_.name == name).map(setInterface))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Produces(Array(APPLICATION_INTERFACE_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[Interface] = {
        getInterfaces(hostId.toString)
            .map(_.map(setInterface).asJava)
            .getOrThrow
    }

    private def setInterface(interface: Interface): Interface = {
        interface.hostId = hostId
        interface.setBaseUri(uriInfo.getBaseUri)
        interface
    }

    private def getInterfaces(hostId: String): Future[Seq[Interface]] = {
        getResourceState(classOf[Host], hostId, MidonetBackend.HostKey).map {
            case SingleValueKey(_, Some(value), _) =>
                val builder = State.HostState.newBuilder()
                TextFormat.merge(value, builder)
                val hostState = ZoomConvert.fromProto(builder.build(),
                                                      classOf[HostState])
                hostState.interfaces.asScala
            case _ => List.empty
        }
    }

}
