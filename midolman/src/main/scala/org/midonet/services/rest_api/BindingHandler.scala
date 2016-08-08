/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.services.rest_api

import java.util.UUID

import javax.ws.rs.core.MediaType
import javax.ws.rs.DELETE
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Consumes
import javax.ws.rs.Produces

import com.google.inject.Inject

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.ZookeeperLockFactory

import RestApiService.log


/*
 * PUT    /binding/:portId
 * DELETE /binding/:portId
 */

@Path("/binding")
class BindingHandler @Inject()(
        lockFactory: ZookeeperLockFactory,
        backend: MidonetBackend) {

    private val binder = new ZoomPortBinder(backend.store, backend.stateStore,
                                            lockFactory)

    @PUT
    @Path("{portId}")
    @Consumes(Array(MediaType.APPLICATION_JSON))
    @Produces(Array(MediaType.APPLICATION_JSON))
    def put(@PathParam("portId") portId: String, body: BindingInfo) = {
        log info s"PUT ${portId} ${body}"
        binder.bindPort(UUID.fromString(portId), body.interfaceName)
        body
    }

    @DELETE
    @Path("{portId}")
    def delete(@PathParam("portId") portId: String) = {
        log info s"DELETE ${portId}"
        binder.unbindPort(UUID.fromString(portId))
    }
}
