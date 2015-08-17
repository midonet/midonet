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

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.{Path, PathParam}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.federation.{Application, VtepGroup}
import FederationMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.midolman.state.PathBuilder

@RequestScoped
@AllowGet(Array(VTEP_GROUP_JSON,
                APPLICATION_JSON))
@AllowList(Array(VTEP_GROUP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(VTEP_GROUP_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(VTEP_GROUP_JSON,
                   APPLICATION_JSON))
@AllowDelete
class VtepGroupResource @Inject()(resContext: ResourceContext,
                                  pathBuilder: PathBuilder,
                                  curator: CuratorFramework)
    extends MidonetResource[VtepGroup](resContext) {

    /*@Path("{id}/" + Application.VXLAN_SEGMENTS)
    def vxlanSegments(@PathParam("id") id: UUID): VxlanSegmentInGroupResource = {
        new VxlanSegmentInGroupResource(id, resContext)
    }

    @Path("{id}/" + Application.OVSDB_VTEPS)
    def ovsdbVteps(@PathParam("id") id: UUID): OvsdbVtepInGroupResource = {
        new OvsdbVtepInGroupResource(id, resContext)
    }

    @Path("{id}/" + Application.MIDONET_VTEPS)
    def midonetVteps(@PathParam("id") id: UUID): MidonetVtepInGroupResource = {
        new MidonetVtepInGroupResource(id, resContext)
    }
    */

}
