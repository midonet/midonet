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
import java.util.{ArrayList, HashMap => JHashMap}

import scala.collection.JavaConverters._
import scala.collection.mutable

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Mirror => TopologyMirror}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.rules.{Condition => SimCond}
import org.midonet.midolman.simulation.{IPAddrGroup => SimIPAddrGroup, Mirror => SimMirror}
import org.midonet.midolman.topology.ChainMapper.IpAddressGroupState
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object MirrorMapper {
}

final class MirrorMapper(id: UUID, vt: VirtualTopology)
        extends VirtualDeviceMapper[SimMirror](id, vt) with MidolmanLogging {

    override def logSource = s"org.midonet.devices.mirror.mirror-$id"

    private var mirrorProto: TopologyMirror = TopologyMirror.newBuilder.build()

    // The stream of IPAddrGroups referenced by the Conditions of this mirror
    private val ipAddrGroupStream = PublishSubject.create[Observable[SimIPAddrGroup]]()
    private val ipAddrGroups = new mutable.HashMap[UUID, IpAddressGroupState]()

    private def subscribeToIpAddrGroup(ipAddrGroupId: UUID) : Option[IpAddressGroupState] = {
        ipAddrGroups get ipAddrGroupId match {
            case Some(ipAddrGroup) =>
                ipAddrGroup.refCount += 1
                None
            case None =>
                log.debug("Subscribing to IP address group: {}", ipAddrGroupId)
                val ipAddrGroupState = new IpAddressGroupState(ipAddrGroupId, vt)
                ipAddrGroups += ipAddrGroupId -> ipAddrGroupState
                Some(ipAddrGroupState)
        }
    }

    private def unsubscribeFromIpAddrGroup(ipAddrGroupId: UUID): Unit = {
        ipAddrGroups get ipAddrGroupId match {
            case Some(ipAddrGroup) if ipAddrGroup.refCount == 1 =>
                log.debug("Unsubscribing from IP address group: {}",
                          ipAddrGroupId)
                ipAddrGroup.complete()
                ipAddrGroups -= ipAddrGroupId
            case Some(ipAddrGroup) => ipAddrGroup.refCount -= 1
            case None =>
                log.warn("IP address group {} does not exist", ipAddrGroupId)
        }
    }

    private def mirrorUpdated(mirror: TopologyMirror): TopologyMirror = {
        assertThread()
        log.debug("Mirror updated")

        val oldGroups = ipAddrGroups.keySet

        val newGroupsSrc =
            Option(mirror.getConditonsList) map
                { _.asScala.toSet } getOrElse Set.empty filter
                { _.hasIpAddrGroupIdSrc } map { _.getIpAddrGroupIdSrc.asJava }

        val newGroupsDst =
            Option(mirror.getConditonsList) map
                { _.asScala.toSet } getOrElse Set.empty filter
                { _.hasIpAddrGroupIdDst } map { _.getIpAddrGroupIdDst.asJava }

        val newGroups = newGroupsSrc ++ newGroupsDst

        val removedGroups = oldGroups -- newGroups
        val addedGroups = newGroups -- oldGroups

        removedGroups foreach unsubscribeFromIpAddrGroup
        addedGroups foreach subscribeToIpAddrGroup

        for (group <- addedGroups) {
            ipAddrGroupStream onNext ipAddrGroups(group).observable
        }

        mirrorProto = mirror
        mirrorProto
    }

    private def mirrorReady(update: Any): Boolean = {
        assertThread()
        val ready = ipAddrGroups.forall(_._2.isReady)
        log.debug(s"Mirror ready: $ready")
        ready && (mirrorProto ne null)
    }

    private def mirrorDeleted() = {
        assertThread()
        log.debug("Mirror deleted")
        ipAddrGroupStream.onCompleted()
        ipAddrGroups.values.foreach(_.complete())
        ipAddrGroups.clear()
    }

    private def buildMirror(update: Any): SimMirror = {
        // Set the IP address groups source and destination addresses in the
        //
        def resolveGroup(id: UUID) = if (id ne null) ipAddrGroups(id).ipAddressGroup
                                     else null

        val condArray = new ArrayList[SimCond]()
        for (cond <- mirrorProto.getConditonsList.asScala map
                        (ZoomConvert.fromProto(_, classOf[SimCond]))) {
            cond.ipAddrGroupSrc = resolveGroup(cond.ipAddrGroupIdSrc)
            cond.ipAddrGroupDst = resolveGroup(cond.ipAddrGroupIdDst)
            condArray.add(cond)
        }

        val mirror = SimMirror(id, condArray, mirrorProto.getToPort)
        log.info(s"Emitting $mirror")
        mirror
    }

    private lazy val mirrorObservable =
        vt.store.observable(classOf[TopologyMirror], id)
            .observeOn(vt.vtScheduler)
            .map[TopologyMirror](makeFunc1(mirrorUpdated))
            .doOnCompleted(makeAction0(mirrorDeleted()))

    protected override lazy val observable =
        Observable.merge[Any](Observable.merge(ipAddrGroupStream),
                              mirrorObservable)
            .filter(makeFunc1(mirrorReady))
            .map[SimMirror](makeFunc1(buildMirror))
}
