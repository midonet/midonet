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
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{RouterPort, Pool, PoolMember}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

/**
  * All operations that need to be performed atomically are done by
  * acquiring a ZooKeeper lock. This is to prevent races with
  * midolman, more specifically the health monitor, which operates on:
  * - Route, with side effects on Router and RouterPort due to ZOOM bindings.
  * - Port, with side effects on Router and Host due to ZOOM bindings.
  * - Pool
  * - PoolMember
  *
  * Any api or midolman sections that may be doing something like:
  *
  *   val port = store.get(classOf[Port], id)
  *   val portModified = port.toBuilder() -alter fields- .build()
  *   store.update(portModified)
  *
  * Might overwrite changes made by this class on the port (directly, or
  * implicitly as a result of referential constraints).  To protect
  * against this, sections of the code similar to the one above should
  * be protected using the following lock:
  *
  *   new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
  *                ZookeeperLockFactory.ZOOM_TOPOLOGY)
  */
@ApiResource(version = 1)
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
    : Ops = {
        to.update(from)
        NoOps
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_POOL_MEMBER_JSON,
                    APPLICATION_JSON))
    override def update(@PathParam("id") id: String, member: PoolMember,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        lock {
             super.update(id, member, contentType)
        }
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        lock {
            try {
                deleteResource(classOf[PoolMember], id)
            } catch {
                case t: NotFoundHttpException => // ok, idempotent
            }
            MidonetResource.OkNoContentResponse
        }
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_POOL_MEMBER_JSON,
                   APPLICATION_JSON))
class PoolPoolMemberResource @Inject()(poolId: UUID, resCtx: ResourceContext)
    extends MidonetResource[PoolMember](resCtx) {

    protected override def listIds: Ids = {
        getResource(classOf[Pool], poolId) map { _.poolMemberIds.asScala }
    }

    protected override def createFilter(poolMember: PoolMember): Ops = {
        poolMember.create(poolId)
        NoOps
    }

}