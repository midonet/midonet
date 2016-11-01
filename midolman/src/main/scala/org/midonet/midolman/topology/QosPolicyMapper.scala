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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.breakOut

import rx.Observable

import org.midonet.cluster.models.Topology.{QosPolicy, QosRuleBandwidthLimit, QosRuleDscp}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{QosMaxBandwidthRule, QosDscpRule, QosPolicy => SimQosPolicy}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object QosPolicyMapper {
    // If burst is not provided by user, default to this proportion of rate.
    val DefaultBurstRatio = 0.8
}

class QosPolicyMapper(id: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper(classOf[SimQosPolicy], id, vt)
            with MidolmanLogging {
    import QosPolicyMapper._

    override def logSource: String = "org.midonet.devices.qos-policy"
    override def logMark: String = s"qos-policy:$id"

    private val bandwidthRuleTracker =
        new StoreObjectReferenceTracker(vt, classOf[QosRuleBandwidthLimit], log)
    private val dscpRuleTracker =
        new StoreObjectReferenceTracker(vt, classOf[QosRuleDscp], log)

    private var qosPolicy: QosPolicy = _
    private val qosPolicyObservable =
        vt.store.observable(classOf[QosPolicy], id)
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(qosPolicyDeleted()))
            .doOnNext(makeAction1(qosPolicyUpdated))

    override def observable: Observable[SimQosPolicy] =
        Observable.merge[AnyRef](bandwidthRuleTracker.refsObservable,
                                 dscpRuleTracker.refsObservable,
                                 qosPolicyObservable)
            .observeOn(vt.vtScheduler)
            .filter(makeFunc1(isReady))
            .map[SimQosPolicy](makeFunc1(buildQosPolicy))
            .distinctUntilChanged()

    private def isReady(ignored: AnyRef): Boolean = {
        assertThread()
        bandwidthRuleTracker.areRefsReady && dscpRuleTracker.areRefsReady
    }

    private def qosPolicyDeleted(): Unit = {
        assertThread()
        log.debug(s"QoS policy $id deleted")
        bandwidthRuleTracker.completeRefs()
        dscpRuleTracker.completeRefs()
    }

    private def qosPolicyUpdated(p: QosPolicy): Unit = {
        assertThread()
        log.debug(s"QoS policy $id updated")
        val bwRuleIds: Set[UUID] =
            p.getBandwidthLimitRuleIdsList.asScala.map(_.asJava)(breakOut)
        val dscpRuleIds: Set[UUID] =
            p.getDscpMarkingRuleIdsList.asScala.map(_.asJava)(breakOut)
        bandwidthRuleTracker.requestRefs(bwRuleIds)
        dscpRuleTracker.requestRefs(dscpRuleIds)
        qosPolicy = p
    }

    private def buildQosPolicy(ignored: Any): SimQosPolicy = {
        assertThread()

        val bwRules = for ((id, r) <- bandwidthRuleTracker.currentRefs) yield {
            val burst = if (r.hasMaxBurstKbps) {
                r.getMaxBurstKbps
            } else {
                (r.getMaxKbps * DefaultBurstRatio).ceil.toInt
            }
            QosMaxBandwidthRule(id, r.getMaxKbps, burst)
        }

        val dscpRules = for ((id, r) <- dscpRuleTracker.currentRefs) yield {
            QosDscpRule(id, r.getDscpMark.toByte)
        }

        val simQosPolicy = SimQosPolicy(qosPolicy.getId.asJava,
                                        qosPolicy.getName,
                                        bwRules.toSeq,
                                        dscpRules.toSeq)

        log.debug(s"Emitting $simQosPolicy")
        simQosPolicy
    }
}
