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
import javax.ws.rs.core.Response
import javax.ws.rs.{PathParam, Path, DELETE}
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Pool, PoolMember}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{Multi, ResourceContext}

@ApiResource(version = 1, name = "poolMembers", template = "poolMemberTemplate")
@Path("pool_members")
@RequestScoped
@AllowGet(Array(APPLICATION_POOL_MEMBER_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
class PoolMemberResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[PoolMember](resContext) {

    protected override def updateFilter(to: PoolMember, from: PoolMember)
    : Seq[Multi] = {
        to.update(from)
        Seq.empty
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = zkLock {
        try {
            deleteResource(classOf[PoolMember], id)
        } catch {
            case t: NotFoundHttpException => // ok, idempotent
        }
        MidonetResource.OkNoContentResponse
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
class PoolPoolMemberResource @Inject()(poolId: UUID, resCtx: ResourceContext)
    extends MidonetResource[PoolMember](resCtx) {

    protected override def listIds: Seq[Any] = {
        getResource(classOf[Pool], poolId).poolMemberIds.asScala
    }

    protected override def createFilter(poolMember: PoolMember): Seq[Multi] = {
        poolMember.create(poolId)
        Seq.empty
    }

}