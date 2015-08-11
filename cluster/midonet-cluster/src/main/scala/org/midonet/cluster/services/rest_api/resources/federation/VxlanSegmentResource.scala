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

package org.midonet.cluster.services.rest_api.resources.federation

import java.util.UUID

import javax.ws.rs.{PathParam, Path}
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.federation.VxlanSegment
import org.midonet.cluster.services.rest_api.resources.MidonetResource
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.services.rest_api.resources.federation.FederationMediaTypes._

@ApiResource(version = 1)
@Path("vxlan_segments")
@RequestScoped
@AllowGet(Array(VXLAN_SEGMENT_JSON,
                APPLICATION_JSON))
@AllowList(Array(VXLAN_SEGMENT_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(VXLAN_SEGMENT_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(VXLAN_SEGMENT_JSON,
                   APPLICATION_JSON))
@AllowDelete
class VxlanSegmentResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[VxlanSegment](resContext) {

    @Path("{id}/ovsdb_bindings")
    def ovsdbBindings(@PathParam("id") id: UUID): SegmentOvsdbBindingResource = {
        new SegmentOvsdbBindingResource(id, resContext)
    }

    @Path("{id}/midonet_bindings")
    def midonetBindings(@PathParam("id") id: UUID): SegmentMidonetBindingResource = {
        new SegmentMidonetBindingResource(id, resContext)
    }
}