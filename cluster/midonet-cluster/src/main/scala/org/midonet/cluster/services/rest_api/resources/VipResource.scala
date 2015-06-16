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

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Pool, Vip}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

/** Just a utility trait to share the create filter among all resources. */
trait VipResourceCreateFilter extends MidonetResource[Vip] {
    protected[this] val store: Storage
    protected override def createFilter = (vip: Vip) => {
        vip.loadBalancerId = getResource(classOf[Pool], vip.poolId)
                                .getOrThrow.loadBalancerId
        vip.create(vip.poolId)
    }
}

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
    extends MidonetResource[Vip](resContext) with VipResourceCreateFilter {

    protected[this] val store = resContext.backend.store

    protected override def updateFilter = (to: Vip, from: Vip) => {
        to.update(from)
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_VIP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
class PoolVipResource @Inject()(poolId: UUID, resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) with VipResourceCreateFilter {

    protected[this] val store = resContext.backend.store

    protected override def listFilter = (vip: Vip) => {
        vip.poolId == poolId
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_VIP_COLLECTION_JSON,
                 APPLICATION_JSON))
class LoadBalancerVipResource @Inject()(loadBalancerId: UUID,
                                        resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) with VipResourceCreateFilter {

    protected[this] val store = resContext.backend.store

    protected override def listFilter = (vip: Vip) => {
        vip.loadBalancerId == loadBalancerId
    }

}