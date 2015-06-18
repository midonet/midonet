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

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Response, UriInfo}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.Update

class AbstractPortResource[P >: Null <: Port]
                          (backend: MidonetBackend, uriInfo: UriInfo)
                          (implicit tag: ClassTag[P])
    extends MidonetResource[P](backend, uriInfo)(tag) {

    protected override def getFilter = (port: P) => setActive(port)

    protected override def listFilter = (port: P) => { setActive(port); true }

    private def isActive(id: String): Boolean = {
        getResourceOwners(classOf[Port], id).getOrThrow.nonEmpty
    }

    private def setActive(port: P): P = {
        port.active = isActive(port.id.toString)
        port
    }

}

@RequestScoped
@AllowGet(Array(APPLICATION_PORT_JSON,
                APPLICATION_PORT_V2_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PortResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends AbstractPortResource[Port](backend, uriInfo) {

    @POST
    @Path("{id}/link")
    @Consumes(Array(APPLICATION_PORT_LINK_JSON,
                    APPLICATION_JSON))
    def link(@PathParam("id") id: UUID, link: Link): Response = {
        getResource(classOf[Port], id).map(port => {
            port.peerId = link.peerId
            updateResource(port)
        }).getOrThrow
        // We want a CREATED, not an UPDATED code.
        Response.created(link.getUri).build()
    }

    @DELETE
    @Path("{id}/link")
    def unlink(@PathParam("id") id: UUID): Response = {
        getResource(classOf[Port], id).map(port => {
            port.peerId = null
            updateResource(port)
        }).getOrThrow
        // We want a CREATED, not an UPDATED code.
        MidonetResource.OkNoContentResponse
    }

    @Path("{id}/port_groups")
    def portGroups(@PathParam("id") id: UUID): PortPortGroupResource = {
        new PortPortGroupResource(id, backend, uriInfo)
    }

    protected override def updateFilter = (to: Port, from: Port) => {
        to.update(from)
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class BridgePortResource @Inject()(bridgeId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends AbstractPortResource[BridgePort](backend, uriInfo) {

    protected override def listFilter = (port: Port) => {
        port.getDeviceId == bridgeId
    }

    protected override def createFilter = (port: BridgePort) => {
        port.create(bridgeId)
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class RouterPortResource @Inject()(routerId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends AbstractPortResource[RouterPort](backend, uriInfo) {

    protected override def listFilter = (port: Port) => {
        port.getDeviceId == routerId
    }

    protected override def createFilter = (port: RouterPort) => {
        port.create(routerId)
    }

}

@RequestScoped
class PortGroupPortResource @Inject()(portGroupId: UUID,
                                      backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource[PortGroupPort](backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_PORTGROUP_PORT_JSON,
                    APPLICATION_JSON))
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): PortGroupPort = {
        val portId = UUID.fromString(id)
        val portGroupPort = getResource(classOf[PortGroupPort], portId)
            .getOrThrow
        if (!portGroupPort.portGroupIds.contains(portGroupId)) {
            throw new WebApplicationException(Response.Status.NOT_FOUND)
        }
        portGroupPort.portGroupId = portGroupId
        portGroupPort
    }

    @GET
    @Produces(Array(APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[PortGroupPort] = {
        val portGroupPorts = getResource(classOf[PortGroup], portGroupId)
            .flatMap(pg => listResources(classOf[PortGroupPort],
                                         pg.portIds.asScala))
            .getOrThrow
        portGroupPorts.foreach(portGroupPort => {
            if (!portGroupPort.portGroupIds.contains(portGroupId)) {
                throw new WebApplicationException(Response.Status.NOT_FOUND)
            }
            portGroupPort.portGroupId = portGroupId
        })
        portGroupPorts.asJava
    }

    @POST
    @Consumes(Array(APPLICATION_PORTGROUP_PORT_JSON,
                    APPLICATION_JSON))
    override def create(portGroupPort: PortGroupPort,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        portGroupPort.portGroupId = portGroupId
        getResource(classOf[Port], portGroupPort.portId).flatMap(port => {
            getResource(classOf[PortGroup], portGroupPort.portGroupId).map(pg => {
                if (port.portGroupIds.contains(portGroupPort.portGroupId)) {
                    throw new WebApplicationException(Status.CONFLICT)
                }
                if (pg.portIds.contains(portGroupPort.portId)) {
                    throw new WebApplicationException(Status.CONFLICT)
                }
                portGroupPort.setBaseUri(uriInfo.getBaseUri)
                port.portGroupIds.add(portGroupPort.portGroupId)
                pg.portIds.add(portGroupPort.portId)
                multiResource(Seq(Update(port), Update(pg)),
                              Response.created(portGroupPort.getUri).build())
            })
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        val portId = UUID.fromString(id)
        // If the port still exists, delete the port group ID from the port,
        // otherwise delete only the port ID from the port group.
        getResource(classOf[Port], portId).flatMap(port => {
            getResource(classOf[PortGroup], portGroupId).map(pg => {
                if (!port.portGroupIds.contains(portGroupId)) {
                    throw new WebApplicationException(Status.NOT_FOUND)
                }
                if (!pg.portIds.contains(portId)) {
                    throw new WebApplicationException(Status.NOT_FOUND)
                }
                port.portGroupIds.remove(portGroupId)
                pg.portIds.remove(portId)
                multiResource(Seq(Update(port), Update(pg)))
            })
        }).fallbackTo(
            getResource(classOf[PortGroup], portGroupId).map(pg => {
                if (!pg.portIds.contains(portId)) {
                    throw new WebApplicationException(Status.NOT_FOUND)
                }
                pg.portIds.remove(portId)
                updateResource(pg)
            }))
        .getOrThrow
    }
}