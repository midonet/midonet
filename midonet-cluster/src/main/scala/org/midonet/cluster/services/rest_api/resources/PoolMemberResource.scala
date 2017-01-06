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
import javax.ws.rs.core.Response
import javax.ws.rs.{DELETE, Path, PathParam}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import rx.Observable

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Pool, PoolMember}
import org.midonet.cluster.services.MidonetBackend.StatusKey
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkNoContentResponse, ResourceContext}
import org.midonet.midolman.state.l4lb.LBStatus
import org.midonet.util.functors.makeFunc1

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

    protected override def updateFilter(to: PoolMember, from: PoolMember,
                                        tx: ResourceTransaction): Unit = {
        to.update(from)
        tx.update(to)
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = tryTx { tx =>
        try {
            tx.delete(classOf[PoolMember], id)
        } catch {
            case t: NotFoundHttpException => // ok, idempotent
        }
        OkNoContentResponse
    }

    override protected def getFilter(pm: PoolMember): PoolMember = {
        val updates = getStatus(pm).toList.toBlocking.first().asScala
        updates.foreach { case (m, sk) => m.status = toStatus(sk) }
        pm
    }

    override protected def listFilter(pms: Seq[PoolMember]): Seq[PoolMember] = {
        val updates = Observable.merge(pms.map(getStatus).asJava)
            .toList.toBlocking.first().asScala
        updates.foreach { case (m, sk) => m.status = toStatus(sk) }
        pms
    }

    /**
      * If pm is a v1 PoolMember (status set in the PoolMember protobuf
      * message), returns an observable that immediately completes.
      *
      * If pm is a v2 PoolMember with adminStateUp == false, sets pm's status
      * field to INACTIVE and returns an observable that immediately completes.
      *
      * Otherwise returns an observable emitting the tuple (pm, sk), where
      * pm is the argument to getStatus, and sk is the StateKey for its status
      * property.
      */
    private def getStatus(pm: PoolMember)
    : Observable[(PoolMember, StateKey)] = {
        if (pm.status != LBStatus.MONITORED) {
            Observable.empty()
        } else if (!pm.adminStateUp) {
            pm.status = LBStatus.INACTIVE
            Observable.empty()
        } else {
            stateStore.getKey(classOf[Topology.PoolMember], pm.id, StatusKey)
                .map(makeFunc1((pm, _)))
        }
    }

    private def toStatus(sk: StateKey): LBStatus = {
        if (sk.isEmpty) {
            // Key not found, which means the pool member is not being actively
            // monitored. Either no HM is assigned, or the assigned HM is down
            // or has not yet started.
            LBStatus.NO_MONITOR
        } else {
            LBStatus.valueOf(sk.asInstanceOf[SingleValueKey].value.get)
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

    protected override def listIds: Seq[Any] = {
        getResource(classOf[Pool], poolId).poolMemberIds.asScala
    }

    protected override def validationFilter(poolMember: PoolMember,
                                            tx: ResourceTransaction): Unit = {
        poolMember.create(poolId)
    }

}
