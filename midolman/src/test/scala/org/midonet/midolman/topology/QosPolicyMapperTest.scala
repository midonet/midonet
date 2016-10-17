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
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.models.Topology.{Port, QosPolicy, QosRuleBandwidthLimit, QosRuleDscp}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil.{RichJavaUuid, asRichProtoUuid}
import org.midonet.midolman.simulation.{Bridge => SimBridge, Port => SimPort, QosPolicy => SimQosPolicy}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.reactivex.TestAwaitableObserver

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
        scenario("Publishes port with existing QosPolicy") {
            val polId = createQosPolicyAndRules(Seq(BwData(1000, 10000)),
                                                Seq(12))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            val simPort = obs.getOnNextEvents.get(0)

            val simPol = simPort.qosPolicy
            checkSimPolicy(simPol, Seq(BwData(1000, 10000)), Seq(12))
        }

        scenario("Publishes port update that adds QosPolicy") {
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

        scenario("Publishes port update that removes QosPolicy") {
            val polId = createQosPolicyAndRules(Seq(BwData(1000, 10000)),
                                                Seq(12))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            val simPort = obs.getOnNextEvents.get(0)
            simPort.qosPolicy should not be null

            val tpPort =
                vt.store.get(classOf[Port], simPort.id).await()
            vt.store.update(tpPort.toBuilder.clearQosPolicyId().build())
            obs.awaitOnNext(2, timeout)
            val simPortNoPolicy = obs.getOnNextEvents.get(1)
            simPortNoPolicy.qosPolicy shouldBe null
        }

        scenario("Clears port's qosPolicy reference on policy deletion") {
            val polId = createQosPolicyAndRules(Seq(BwData(1000, 10000)),
                                                Seq(12))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            val simPort = obs.getOnNextEvents.get(0)
            simPort.qosPolicy should not be null

            vt.store.delete(classOf[QosPolicy], simPort.qosPolicy.id)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1).qosPolicy shouldBe null
        }

        scenario("Publishes bridge with existing QosPolicy") {
            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(5))
            val b = createBridge(qosPolicyId = Some(polId))
            vt.store.create(b)

            val obs = createBridgeMapperAndObserver(b.getId.asJava)
            obs.awaitOnNext(1, timeout)

            val simPol = obs.getOnNextEvents.get(0).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(10, 1000)), Seq(5))
        }

        scenario("Publishes bridge update that adds QOS policy") {
            val b = createBridge()
            vt.store.create(b)

            val obs = createBridgeMapperAndObserver(b.getId.asJava)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0).qosPolicy shouldBe null

            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(5))
            vt.store.update(b.toBuilder.setQosPolicyId(polId.asProto).build())
            obs.awaitOnNext(2, timeout)

            val simPol = obs.getOnNextEvents.get(1).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(10, 1000)), Seq(5))
        }

        scenario("Pushes bridge update on policy update") {
            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(5))
            val b = createBridge(qosPolicyId = Some(polId))
            vt.store.create(b)

            val obs = createBridgeMapperAndObserver(b.getId.asJava)
            obs.awaitOnNext(1, timeout)
            val simPol = obs.getOnNextEvents.get(0).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(10, 1000)), Seq(5))

            val bwRule2 = createQosRuleBWLimit(polId, 100, 500)
            vt.store.create(bwRule2)
            obs.awaitOnNext(2, timeout)

            val simPol2Bw = obs.getOnNextEvents.get(1).qosPolicy
            checkSimPolicy(simPol2Bw,
                           Seq(BwData(10, 1000), BwData(100, 500)), Seq(5))
        }

        scenario("Publishes bridge update that removes QOS policy") {
            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(5))
            val b = createBridge(qosPolicyId = Some(polId))
            vt.store.create(b)

            val obs = createBridgeMapperAndObserver(b.getId.asJava)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0).qosPolicy should not be null

            vt.store.update(b.toBuilder.clearQosPolicyId().build())
            obs.awaitOnNext(2, timeout)

            obs.getOnNextEvents.get(1).qosPolicy shouldBe null
        }

        scenario("Clears bridge's qosPolicy reference on policy deletion") {
            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(5))
            val b = createBridge(qosPolicyId = Some(polId))
            vt.store.create(b)

            val obs = createBridgeMapperAndObserver(b.getId.asJava)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0).qosPolicy should not be null

            vt.store.delete(classOf[QosPolicy], polId)
            obs.awaitOnNext(2, timeout)

            obs.getOnNextEvents.get(1).qosPolicy shouldBe null
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
            val simPol2Bw = simPort.qosPolicy
            checkSimPolicy(simPol2Bw, Seq(BwData(10, 1000), BwData(100, 500)),
                           Seq(15))

            val dscpRule2 = createQosRuleDscp(polId, 16)
            vt.store.create(dscpRule2)

            obs.awaitOnNext(3, timeout)
            val simPol2Bw2Dscp = obs.getOnNextEvents.get(2).qosPolicy
            checkSimPolicy(simPol2Bw2Dscp,
                           Seq(BwData(10, 1000), BwData(100, 500)), Seq(15, 16))
        }

        scenario("Publishes updates that delete rules") {
            val polId = createQosPolicyAndRules(
                Seq(BwData(10, 1000), BwData(100, 500)), Seq(15, 16))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val simPol2Bw2Dscp = obs.getOnNextEvents.get(0).qosPolicy
            checkSimPolicy(simPol2Bw2Dscp,
                           Seq(BwData(10, 1000), BwData(100, 500)), Seq(15, 16))

            val bwRuleToDel =
                simPol2Bw2Dscp.bandwidthRules.find(_.maxKbps == 10).get
            vt.store.delete(classOf[QosRuleBandwidthLimit], bwRuleToDel.id)

            obs.awaitOnNext(2, timeout)
            val simPol1Bw2Dscp = obs.getOnNextEvents.get(1).qosPolicy
            checkSimPolicy(simPol1Bw2Dscp, Seq(BwData(100, 500)), Seq(15, 16))

            val dscpRuleToDel =
                simPol1Bw2Dscp.dscpRules.find(_.dscpMark == 16).get
            vt.store.delete(classOf[QosRuleDscp], dscpRuleToDel.id)

            obs.awaitOnNext(3, timeout)
            val simPol1Bw1Dscp = obs.getOnNextEvents.get(2).qosPolicy
            checkSimPolicy(simPol1Bw1Dscp, Seq(BwData(100, 500)), Seq(15))
        }

        scenario("Publishes updates that modify rules") {
            val polId = createQosPolicyAndRules(
                Seq(BwData(10, 1000), BwData(100, 500)), Seq(15, 16))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            val simPol = obs.getOnNextEvents.get(0).qosPolicy

            val simBwRule1 = simPol.bandwidthRules.find(_.maxKbps == 100).get
            val tpBwRule1 =
                vt.store.get(classOf[QosRuleBandwidthLimit], simBwRule1.id).await()
            vt.store.update(tpBwRule1.toBuilder.setMaxKbps(50).build())

            obs.awaitOnNext(2, timeout)
            val simPolBwChanged = obs.getOnNextEvents.get(1).qosPolicy
            checkSimPolicy(simPolBwChanged,
                           Seq(BwData(10, 1000), BwData(50, 500)), Seq(15, 16))

            val simDscpRule1 = simPol.dscpRules.find(_.dscpMark == 15).get
            val tpDscpRule1 =
                vt.store.get(classOf[QosRuleDscp], simDscpRule1.id).await()
            vt.store.update(tpDscpRule1.toBuilder.setDscpMark(30).build())

            obs.awaitOnNext(3, timeout)
            val simPolDscpChanged = obs.getOnNextEvents.get(2).qosPolicy
            checkSimPolicy(simPolDscpChanged,
                           Seq(BwData(10, 1000), BwData(50, 500)), Seq(30, 16))
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

    private def createBridgeMapperAndObserver(bridgeId: UUID)
    : TestAwaitableObserver[SimBridge] = {
        val mapper = new BridgeMapper(bridgeId, vt, mutable.Map())
        val obs = new TestAwaitableObserver[SimBridge]
        Observable.create(mapper).subscribe(obs)
        obs
    }

    private def checkSimPolicy(simPol: SimQosPolicy,
                               bwData: Seq[BwData],
                               dscpMarks: Seq[Int]): Unit = {
        val pol = vt.store.get(classOf[QosPolicy], simPol.id).await()
        simPol.name shouldBe pol.getName
        simPol.bandwidthRules.map(_.id) should contain theSameElementsAs
            pol.getBandwidthLimitRuleIdsList.asScala.map(_.asJava)
        simPol.dscpRules.map(_.id) should contain theSameElementsAs
            pol.getDscpMarkingRuleIdsList.asScala.map(_.asJava)

        val simPolBwData =
            simPol.bandwidthRules.map(r => BwData(r.maxKbps, r.maxBurstKbps))
        simPolBwData should contain theSameElementsAs bwData

        val simPolDscpMarks = simPol.dscpRules.map(_.dscpMark)
        simPolDscpMarks should contain theSameElementsAs simPolDscpMarks
    }
}
