/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID

import org.midonet.cluster.rest_api.{NotFoundHttpException, ConflictHttpException}
import org.midonet.cluster.rest_api.neutron.models.{BgpSpeaker, BgpPeer}

trait BgpApi {

    @throws(classOf[NotFoundHttpException])
    def getBgpSpeaker(id: UUID): BgpSpeaker

    def getBgpSpeakers: util.List[BgpSpeaker]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createBgpSpeaker(bgpSpeaker: BgpSpeaker): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateBgpSpeaker(bgpSpeaker: BgpSpeaker): Unit

    def deleteBgpSpeaker(id: UUID): Unit

    @throws(classOf[NotFoundHttpException])
    def getBgpPeer(id: UUID): BgpPeer

    def getBgpPeers: util.List[BgpPeer]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createBgpPeer(bgpPeer: BgpPeer): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateBgpPeer(bgpPeer: BgpPeer): Unit

    def deleteBgpPeer(id: UUID): Unit
}
