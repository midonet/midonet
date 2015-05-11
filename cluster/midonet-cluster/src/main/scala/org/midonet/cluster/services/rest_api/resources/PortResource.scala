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

import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{BridgePort, Link, Port, RouterPort}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

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
    extends MidonetResource[Port](backend, uriInfo) {

    @POST
    @Path("{id}/link")
    @Consumes(Array(APPLICATION_PORT_LINK_JSON,
                    APPLICATION_JSON))
    def link(@PathParam("id") id: UUID, link: Link): Response = {
        getResource(classOf[Port], id).map(port => {
            port.peerId = link.peerId
            updateResource(port)
        }).getOrThrow
    }

    @DELETE
    @Path("{id}/link")
    def unlink(@PathParam("id") id: UUID): Response = {
        getResource(classOf[Port], id).map(port => {
            port.peerId = null
            updateResource(port)
        }).getOrThrow
    }

    protected override def updateFilter = (to: Port, from: Port) => {
        to.update(from)
    }

}

@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class BridgePortResource @Inject()(bridgeId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends MidonetResource[BridgePort](backend, uriInfo) {

    protected override def listFilter = (port: Port) => {
        port.getDeviceId == bridgeId
    }

    protected override def createFilter = (port: BridgePort) => {
        port.create(bridgeId)
    }
}

@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class RouterPortResource @Inject()(routerId: UUID, backend: MidonetBackend,
                                   uriInfo: UriInfo)
    extends MidonetResource[RouterPort](backend, uriInfo) {

    protected override def listFilter = (port: Port) => {
        port.getDeviceId == routerId
    }

    protected override def createFilter = (port: RouterPort) => {
        port.create(routerId)
    }

}
