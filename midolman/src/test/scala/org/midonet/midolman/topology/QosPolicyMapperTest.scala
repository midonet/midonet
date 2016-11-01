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

import org.midonet.cluster.models.Topology.{Port, QosPolicy, QosRuleBandwidthLimit, QosRuleDscp, _}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil.{RichJavaUuid, asRichProtoUuid}
import org.midonet.midolman.simulation.{Port => SimPort, QosPolicy => SimQosPolicy}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class QosPolicyMapperTest extends MidolmanSpec
                                  with TopologyBuilder
                                  with TopologyMatchers {
    private val timeout: Duration = 1 second
    private var vt: VirtualTopology = _

    implicit def toOption[T](t: T): Option[T] = Option(t)

    /**
      * Override this function to perform a custom set-up needed for the test.
      */
    override protected def beforeTest(): Unit = {
        super.beforeTest()
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("QosPolicyMapper") {
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

        scenario("Publishes port update that adds QOSPolicy") {
            val portId = createBridgeAndPort()
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

        scenario("Port inherits bridge's policy if it doesn't have its own") {
            val polId = createQosPolicyAndRules(Seq(BwData(10, 1000)), Seq(5))
            val portId = createBridgeAndPort(bridgePolId = polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val simPol = obs.getOnNextEvents.get(0).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(10, 1000)), Seq(5))
        }

        scenario("Port QOS policy overrides bridge's QOS policy") {
            val pPolId = createQosPolicyAndRules(Seq(BwData(5, 10)), Seq(5))
            val bPolId = createQosPolicyAndRules(Seq(BwData(4, 8)), Seq(2))
            val portId = createBridgeAndPort(pPolId, bPolId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val simPol = obs.getOnNextEvents.get(0).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(5, 10)), Seq(5))
        }

        scenario("Updating bridge's policy updates port if port has no policy") {
            val portId = createBridgeAndPort()

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val simPort = obs.getOnNextEvents.get(0)
            simPort.qosPolicy shouldBe null

            // Update bridge to add policy.
            val bPolId = createQosPolicyAndRules(Seq(BwData(4, 8)), Seq(2))
            val b = vt.store.get(classOf[Network], simPort.deviceId).await()
            vt.store.update(b.toBuilder.setQosPolicyId(bPolId.asProto).build())

            obs.awaitOnNext(2, timeout)
            val simPol = obs.getOnNextEvents.get(1).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(4, 8)), Seq(2))

            // Update bridge to new policy ID.
            val bPolId2 = createQosPolicyAndRules(Seq(BwData(5, 10)), Seq(3))
            vt.store.update(b.toBuilder.setQosPolicyId(bPolId2.asProto).build())

            obs.awaitOnNext(3, timeout)
            val simPol2 = obs.getOnNextEvents.get(2).qosPolicy
            checkSimPolicy(simPol2, Seq(BwData(5, 10)), Seq(3))

            // Update policy itself.
            val tpPolicy = vt.store.get(classOf[QosPolicy], bPolId2).await()
            vt.store.update(
                tpPolicy.toBuilder.clearDscpMarkingRuleIds().build())

            obs.awaitOnNext(4, timeout)
            val simPol2NoDscp = obs.getOnNextEvents.get(3).qosPolicy
            checkSimPolicy(simPol2NoDscp, Seq(BwData(5, 10)), Seq())

            // Clear bridge's policy ID
            vt.store.update(b.toBuilder.clearQosPolicyId().build())
            obs.awaitOnNext(5, timeout)
            obs.getOnNextEvents.get(4).qosPolicy shouldBe null
        }

        scenario("Updating bridge's policy has no effect if port has policy") {
            val pPolId = createQosPolicyAndRules(Seq(BwData(5, 10)), Seq(5))
            val portId = createBridgeAndPort(portPolId = pPolId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val simPort = obs.getOnNextEvents.get(0)
            checkSimPolicy(simPort.qosPolicy, Seq(BwData(5, 10)), Seq(5))

            // Update bridge to add policy.
            val bPolId = createQosPolicyAndRules(Seq(BwData(4, 8)), Seq(2))
            val b = vt.store.get(classOf[Network], simPort.deviceId).await()
            vt.store.update(b.toBuilder.setQosPolicyId(bPolId.asProto).build())

            // Update bridge to new policy ID.
            val bPolId2 = createQosPolicyAndRules(Seq(BwData(5, 10)), Seq(3))
            vt.store.update(b.toBuilder.setQosPolicyId(bPolId2.asProto).build())

            // Update policy itself.
            val tpPolicy = vt.store.get(classOf[QosPolicy], bPolId2).await()
            vt.store.update(
                tpPolicy.toBuilder.clearDscpMarkingRuleIds().build())

            // Clear bridge's policy ID
            vt.store.update(b.toBuilder.clearQosPolicyId().build())

            // None of these should have changed anything.
            // Update an unrelated property of the port to check.
            val p = vt.store.get(classOf[Port], simPort.id).await()
            vt.store.update(p.toBuilder.setInterfaceName("ifName").build())
            obs.awaitOnNext(2, timeout)

            val simPortUpdated = obs.getOnNextEvents.get(1)
            simPortUpdated.interfaceName shouldBe "ifName"
            checkSimPolicy(simPortUpdated.qosPolicy, Seq(BwData(5, 10)), Seq(3))
        }

        scenario("Adding port policy replaces bridge policy") {
            val bPolId = createQosPolicyAndRules(Seq(BwData(4, 8)), Seq(2))
            val portId = createBridgeAndPort(bridgePolId = bPolId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            checkSimPolicy(obs.getOnNextEvents.get(0).qosPolicy,
                           Seq(BwData(4, 8)), Seq(2))

            val pPolId = createQosPolicyAndRules(Seq(BwData(5, 10)), Seq(5))
            val tpPort = vt.store.get(classOf[Port], portId).await()
            vt.store.update(
                tpPort.toBuilder.setQosPolicyId(pPolId.asProto).build())

            obs.awaitOnNext(2, timeout)
            checkSimPolicy(obs.getOnNextEvents.get(1).qosPolicy,
                           Seq(BwData(5, 10)), Seq(5))
        }

        scenario("Removing port policy reverts to bridge policy") {
            val bPolId = createQosPolicyAndRules(Seq(BwData(4, 8)), Seq(2))
            val pPolId = createQosPolicyAndRules(Seq(BwData(5, 10)), Seq(5))
            val portId = createBridgeAndPort(pPolId, bPolId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            checkSimPolicy(obs.getOnNextEvents.get(0).qosPolicy,
                           Seq(BwData(5, 10)), Seq(5))

            val tpPort = vt.store.get(classOf[Port], portId).await()
            vt.store.update(tpPort.toBuilder.clearQosPolicyId().build())

            obs.awaitOnNext(2, timeout)
            checkSimPolicy(obs.getOnNextEvents.get(1).qosPolicy,
                           Seq(BwData(4, 8)), Seq(2))
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

        // This was a problem with the first draft implementation: Updating some
        // unrelated property of the port would result in the policy it
        // inherited from the bridge being removed.
        scenario("Unrelated port updates do not affect inheritance of QOS " +
                 "policy from bridge") {
            val polId = createQosPolicyAndRules(Seq(BwData(1, 10)), Seq(2))
            val portId = createBridgeAndPort(bridgePolId = polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)
            checkSimPolicy(obs.getOnNextEvents.get(0).qosPolicy,
                           Seq(BwData(1, 10)), Seq(2))

            val tpPort = vt.store.get(classOf[Port], portId).await()
            vt.store.update(tpPort.toBuilder.setTunnelKey(10L).build())
            obs.awaitOnNext(2, timeout)
            checkSimPolicy(obs.getOnNextEvents.get(1).qosPolicy,
                           Seq(BwData(1, 10)), Seq(2))
        }

        scenario("Burst defaults to 80% of rate if not specified") {
            val polId = createQosPolicyAndRules(Seq(BwData(1000, None)),
                                                Seq(12))
            val portId = createBridgeAndPort(polId)

            val obs = createPortMapperAndObserver(portId)
            obs.awaitOnNext(1, timeout)

            val simPol = obs.getOnNextEvents.get(0).qosPolicy
            checkSimPolicy(simPol, Seq(BwData(1000, 800)), Seq(12))

            // Update rule to set burst explicitly.
            val bwRuleId = simPol.bandwidthRules.head.id
            val tpBwRule =
                vt.store.get(classOf[QosRuleBandwidthLimit], bwRuleId).await()
            vt.store.update(tpBwRule.toBuilder.setMaxBurstKbps(2000).build())
            obs.awaitOnNext(2, timeout)

            val simPolBurstSet = obs.getOnNextEvents.get(1).qosPolicy
            checkSimPolicy(simPolBurstSet, Seq(BwData(1000, 2000)), Seq(12))

            // Update rule to clear burst and let default take over again.
            tpBwRule.hasMaxBurstKbps shouldBe false
            vt.store.update(tpBwRule)
            obs.awaitOnNext(3, timeout)

            val simPolBurstCleared = obs.getOnNextEvents.get(2).qosPolicy
            checkSimPolicy(simPolBurstCleared, Seq(BwData(1000, 800)), Seq(12))
        }
    }

    case class BwData(maxKbps: Int, maxBurstKbps: Option[Int])
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

    private def createBridgeAndPort(portPolId: Option[UUID] = None,
                                    bridgePolId: Option[UUID] = None): UUID = {
        val b = createBridge(qosPolicyId = bridgePolId)
        vt.store.create(b)
        val p = createBridgePort(bridgeId = b.getId.asJava,
                                 qosPolicyId = portPolId)
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
