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

import scala.concurrent.duration.{Duration, DurationInt}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.models.Topology.{Port, QOSPolicy, QOSRuleBWLimit, QOSRuleDSCP}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, RichJavaUuid}
import org.midonet.midolman.simulation.{Port => SimPort, QosBandwidthRule => SimBwRule, QosDscpRule => SimDscpRule, QosPolicy => SimQosPolicy}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.util.concurrent.toFutureOps
import scala.collection.JavaConverters._
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class QosPolicyMapperTest extends MidolmanSpec
                                  with TopologyBuilder
                                  with TopologyMatchers
                                  with MidonetEventually {
    private val timeout: Duration = 1 second
    private var vt: VirtualTopology = _

    /**
      * Override this function to perform a custom set-up needed for the test.
      */
    override protected def beforeTest(): Unit = {
        super.beforeTest()
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("QOSPolicyMapper") {
        scenario("Publishes port with existing QOSPolicy") {
            val polId = createQosPolicyAndRules(Seq(BwData(1000, 10000)),
                                                Seq(12))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            val simPort = obs.getOnNextEvents.get(0)

            val simPol = simPort.qosPolicy
            checkSimPolicy(simPol, Seq(BwData(1000, 10000)), Seq(12))
        }

        scenario("Publishes port update that adds QOSPolicy") {
            val portId = createBridgeAndPort(null)
            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val polId = createQosPolicyAndRules(Seq(BwData(50, 500)), Seq(37))

            // Update port to reference policy.
            val port = vt.store.get(classOf[Port], portId).await()
            val portWithPolicy =
                port.toBuilder.setQosPolicyId(polId.asProto).build()
            vt.store.update(portWithPolicy)

            obs.awaitOnNext(2, timeout)
            val simPort = obs.getOnNextEvents.get(1)
            val simPol = simPort.qosPolicy
            checkSimPolicy(simPol, Seq(BwData(50, 500)), Seq(37))
        }

        scenario("Publishes updates that add rules") {
            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(15))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val bwRule2 = createQosRuleBWLimit(polId, 100, 500)
            vt.store.create(bwRule2)

            obs.awaitOnNext(2, timeout)

            val simPort = obs.getOnNextEvents.get(1)
            val simPol = simPort.qosPolicy
            checkSimPolicy(simPol, Seq(BwData(10, 1000), BwData(100, 500)),
                           Seq(15))

            val dscpRule2 = createQosRuleDscp(polId, 16)

        }
    }

    case class BwData(maxKbps: Int, maxBurstKbps: Int)
    private def createQosPolicyAndRules(bwData: Seq[BwData],
                                        dscpMarks: Seq[Int]): UUID = {
        val pol = createQosPolicy()
        vt.store.create(pol)

        val polId = pol.getId.asJava
        for (bw <- bwData) {
            vt.store.create(
                createQosRuleBWLimit(polId, bw.maxKbps, bw.maxBurstKbps))
        }

        for (mark <- dscpMarks) {
            vt.store.create(createQosRuleDscp(polId, mark))
        }

        polId
    }

    private def createBridgeAndPort(polId: UUID): UUID = {
        val b = createBridge()
        vt.store.create(b)
        val p = createBridgePort(bridgeId = Some(b.getId.asJava),
                                 qosPolicyId = Option(polId))
        vt.store.create(p)
        p.getId.asJava
    }

    private def createPortMapperAndObserver(portId: UUID)
    : TestAwaitableObserver[SimPort] = {
        val mapper = new PortMapper(portId, vt, mutable.Map())
        val obs = new TestAwaitableObserver[SimPort]
        Observable.create(mapper).subscribe(obs)
        obs
    }

    private def checkSimPolicy(simPol: SimQosPolicy,
                               bwData: Seq[BwData],
                               dscpMarks: Seq[Int]): Unit = {
        val pol = vt.store.get(classOf[QOSPolicy], simPol.id).await()
        simPol.name shouldBe pol.getName
        simPol.bandwidthRules.map(_.id) shouldBe
            pol.getBandwidthLimitRuleIdsList.asScala.map(_.asJava)
        simPol.dscpRules.map(_.id) shouldBe
            pol.getDscpMarkingRuleIdsList.asScala.map(_.asJava)

        simPol.bandwidthRules.length shouldBe bwData.length
        for ((bw, i) <- bwData.zipWithIndex) {
            val r = simPol.bandwidthRules(i)
            r.maxKbps shouldBe bw.maxKbps
            r.maxBurstKbps shouldBe bw.maxBurstKbps
        }

        simPol.dscpRules.length shouldBe dscpMarks.length
        for ((dscpMark, i) <- dscpMarks.zipWithIndex) {
            val r = simPol.dscpRules(i)
            r.dscpMark shouldBe dscpMark
        }
    }
}
