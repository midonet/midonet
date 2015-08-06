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

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.BgpPeer
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowGet(Array(APPLICATION_BGP_PEER_JSON,
                APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_BGP_PEER_JSON,
                   APPLICATION_JSON))
@AllowDelete
class BgpPeerResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[BgpPeer](resContext) {

    protected override def updateFilter(to: BgpPeer, from: BgpPeer): Unit = {
        to.update(from)
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_BGP_PEER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_BGP_PEER_JSON,
                   APPLICATION_JSON))
class RouterBgpPeerResource @Inject()(routerId: UUID,
                                      resContext: ResourceContext)
    extends MidonetResource[BgpPeer](resContext) {

    protected override def listFilter(bgpPeer: BgpPeer): Boolean = {
        bgpPeer.routerId == routerId
    }

    protected override def createFilter(bgpPeer: BgpPeer): Unit = {
        bgpPeer.create(routerId)
    }

}