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

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.UriInfo

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.PoolMember
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
@AllowGet(Array(APPLICATION_POOL_MEMBER_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PoolMemberResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource[PoolMember](backend, uriInfo) {

    protected override def updateFilter = (to: PoolMember, from: PoolMember) => {
        to.update(from)
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
class PoolPoolMemberResource @Inject()(poolId: UUID, backend: MidonetBackend,
                                       uriInfo: UriInfo)
    extends MidonetResource[PoolMember](backend, uriInfo) {

    protected override def listFilter = (poolMember: PoolMember) => {
        poolMember.poolId == poolId
    }

    protected override def createFilter = (poolMember: PoolMember) => {
        poolMember.create(poolId)
    }

}