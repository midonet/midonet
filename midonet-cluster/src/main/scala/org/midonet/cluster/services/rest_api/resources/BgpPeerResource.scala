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

import javax.ws.rs.Path
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{BgpPeer, Router}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.IPAddr

@ApiResource(version = 1, template = "bgpPeerTemplate")
@Path("bgp_peers")
@RequestScoped
@AllowGet(Array(APPLICATION_BGP_PEER_JSON,
                APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_BGP_PEER_JSON,
                   APPLICATION_JSON))
@AllowDelete
class BgpPeerResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[BgpPeer](resContext) {

    protected override def updateFilter(to: BgpPeer, from: BgpPeer,
                                        tx: ResourceTransaction): Unit = {
        to.update(from)
        tx.update(to)
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

    protected override def listIds: Seq[Any] = {
        getResource(classOf[Router], routerId).bgpPeerIds.asScala
    }

    protected override def createFilter(bgpPeer: BgpPeer,
                                        tx: ResourceTransaction): Unit = {
        val router = tx.get(classOf[Router], routerId)
        val peers = tx.getAll(classOf[BgpPeer], router.bgpPeerIds.asScala)

        val address = IPAddr.fromString(bgpPeer.address)
        if (peers.exists(peer => {
            IPAddr.fromString(peer.address) == address &&
            peer.asNumber == bgpPeer.asNumber
        })) {
            throw new BadRequestHttpException(
                getMessage(BGP_PEER_NOT_UNIQUE, address.toString,
                           Int.box(bgpPeer.asNumber)))
        }

        bgpPeer.create(routerId)
        tx.create(bgpPeer)
    }

}