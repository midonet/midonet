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

import javax.ws.rs._
import javax.ws.rs.core.MediaType

import com.google.inject.Inject

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.services.MidonetBackend
import org.midonet.conf.HostIdGenerator
import org.midonet.services.rest_api.BindingApiService.log


/*
 * PUT    /binding/:portId
 * DELETE /binding/:portId
 */

@Path("/binding")
class BindingHandler @Inject()(
        lockFactory: ZookeeperLockFactory,
        backend: MidonetBackend) {

    private val binder = new PortBinder(backend.store, backend.stateStore,
                                        lockFactory)

    @PUT
    @Path("{portId}")
    @Consumes(Array(MediaType.APPLICATION_JSON))
    @Produces(Array(MediaType.APPLICATION_JSON))
    def put(@PathParam("portId") portId: String, body: BindingInfo) = {
        log debug s"PUT $portId $body"
        binder.bindPort(UUID.fromString(portId), HostIdGenerator.getHostId,
                        body.interfaceName)
        body
    }

    @DELETE
    @Path("{portId}")
    def delete(@PathParam("portId") portId: String) = {
        log debug s"DELETE $portId"
        binder.unbindPort(UUID.fromString(portId), HostIdGenerator.getHostId)
    }
}
