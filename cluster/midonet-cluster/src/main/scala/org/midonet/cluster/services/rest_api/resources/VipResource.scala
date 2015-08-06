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
import javax.ws.rs.core.Response.Status
import javax.ws.rs.{WebApplicationException, HeaderParam, POST}

import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.rest_api.Status.METHOD_NOT_ALLOWED
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Pool, Vip}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext}

@RequestScoped
@AllowGet(Array(APPLICATION_VIP_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_VIP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
@AllowDelete
class VipResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) {

    protected override def createFilter(vip: Vip): Ops = {
        vip.create()
        throwIfViolationsOn(vip)
        val pool = try {
            getResource(classOf[Pool], vip.poolId).getOrThrow
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(t.getMessage)
        }
        vip.loadBalancerId = pool.loadBalancerId
        NoOps
    }

    protected override def updateFilter(to: Vip, from: Vip): Ops = {
        to.update(from)
        val pool = try {
            getResource(classOf[Pool], to.poolId).getOrThrow
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(t.getMessage)
        }
        to.loadBalancerId = pool.loadBalancerId
        NoOps
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_VIP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
class PoolVipResource @Inject()(poolId: UUID, resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) {

    // TODO: Should be replaced with listIds, once the new L4LB model is used.
    protected override def listFilter(vips: Seq[Vip]): Seq[Vip] = {
        vips filter { _.poolId == poolId }
    }

    protected override def createFilter(vip: Vip): Ops = {
        vip.create()
        throwIfViolationsOn(vip)
        val pool = try {
            getResource(classOf[Pool], vip.poolId).getOrThrow
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(t.getMessage)
        }
        vip.loadBalancerId = pool.loadBalancerId
        NoOps
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_VIP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
class LoadBalancerVipResource @Inject()(loadBalancerId: UUID,
                                        resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) {

    protected override def createFilter(vip: Vip): Ops = {
        Future.failed(new WebApplicationException(METHOD_NOT_ALLOWED.getStatusCode))
    }

    // TODO: Should be replaced with listIds, once the new L4LB model is used.
    protected override def listFilter(vips: Seq[Vip]): Seq[Vip] = {
        vips filter { _.loadBalancerId == loadBalancerId }
    }

}