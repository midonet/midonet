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

package org.midonet.midolman.topology

import java.util.UUID
import java.util.ArrayList
import scala.collection.JavaConverters._
import rx.Observable

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Mirror => TopologyMirror}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.rules.{Condition => SimCond}
import org.midonet.midolman.simulation.{IPAddrGroup => SimIPAddrGroup, Mirror => SimMirror}
import org.midonet.util.functors.{makeAction0, makeFunc1}

final class MirrorMapper(id: UUID, vt: VirtualTopology)
        extends VirtualDeviceMapper[SimMirror](id, vt) with MidolmanLogging {

    override def logSource = s"org.midonet.devices.mirror.mirror-$id"

    private var mirrorProto: TopologyMirror = TopologyMirror.newBuilder.build()

    private val addressGroupsTracker = new ObjectReferenceTracker[SimIPAddrGroup](vt)

    private def mirrorUpdated(mirror: TopologyMirror): TopologyMirror = {
        assertThread()
        log.debug("Mirror updated")

        val newGroupsSrc =
            Option(mirror.getConditionsList) map
                { _.asScala.toSet } getOrElse Set.empty filter
                { _.hasIpAddrGroupIdSrc } map { _.getIpAddrGroupIdSrc.asJava }

        val newGroupsDst =
            Option(mirror.getConditionsList) map
                { _.asScala.toSet } getOrElse Set.empty filter
                { _.hasIpAddrGroupIdDst } map { _.getIpAddrGroupIdDst.asJava }

        val newGroups = newGroupsSrc ++ newGroupsDst

        addressGroupsTracker.requestRefs(newGroups)

        mirrorProto = mirror
        mirrorProto
    }

    private def mirrorReady(update: Any): Boolean = {
        assertThread()
        val ready = (mirrorProto ne null) && addressGroupsTracker.areRefsReady
        log.debug(s"Mirror ready: $ready")
        ready
    }

    private def mirrorDeleted() = {
        assertThread()
        log.debug("Mirror deleted")
        addressGroupsTracker.completeRefs()
    }

    private def buildMirror(update: Any): SimMirror = {
        def resolveGroup(id: UUID) = if (id ne null) addressGroupsTracker.currentRefs(id)
                                     else null

        val condArray = new ArrayList[SimCond]()
        for (cond <- mirrorProto.getConditionsList.asScala map
                        (ZoomConvert.fromProto(_, classOf[SimCond]))) {
            cond.ipAddrGroupSrc = resolveGroup(cond.ipAddrGroupIdSrc)
            cond.ipAddrGroupDst = resolveGroup(cond.ipAddrGroupIdDst)
            condArray.add(cond)
        }

        val mirror = SimMirror(id, condArray, mirrorProto.getToPortId)
        log.info(s"Emitting $mirror")
        mirror
    }

    private lazy val mirrorObservable =
        vt.store.observable(classOf[TopologyMirror], id)
            .observeOn(vt.vtScheduler)
            .map[TopologyMirror](makeFunc1(mirrorUpdated))
            .doOnCompleted(makeAction0(mirrorDeleted()))

    protected override lazy val observable =
        Observable.merge[Any](addressGroupsTracker.refsObservable,
                              mirrorObservable)
            .filter(makeFunc1(mirrorReady))
            .map[SimMirror](makeFunc1(buildMirror))
}
