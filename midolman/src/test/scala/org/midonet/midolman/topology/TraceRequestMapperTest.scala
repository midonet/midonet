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

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

import org.junit.runner.RunWith

import rx.{Observable, Observer}
import rx.observers.{TestObserver, TestSubscriber}
import rx.subjects.Subject

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.{JumpRule, TraceRule}
import org.midonet.midolman.simulation.{Bridge => SimBridge, Chain => SimChain, Port => SimPort, Router => SimRouter}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.util.MidolmanSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TraceRequestMapperTest extends MidolmanSpec {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var chainMap: mutable.Map[UUID,Subject[SimChain,SimChain]] = _

    override def useNewStorageStack = true

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        chainMap = mutable.Map[UUID,Subject[SimChain,SimChain]]()
    }

    feature("Trace request provides a chain to chain mapper") {
        scenario("trace chain mapper emits chain, then doesn't when device deleted") {
            val bridge = newBridge("bridge0")
            val chain = newInboundChainOnBridge("chain0", bridge)
            val traceId = UUID.randomUUID

            vt.store.create(TraceRequest.newBuilder.setId(traceId)
                                .setNetworkId(bridge)
                                .setEnabled(true)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build())

            val subscriber = new TestObserver[Option[UUID]]
            val mapper = new TraceRequestTestMapper(bridge, vt, chainMap)
            mapper.subscribe(subscriber)
            mapper.requestForBridge()

            subscriber.getOnNextEvents.size shouldBe 1
            val traceChainId = subscriber.getOnNextEvents.get(0).get

            val chainObj = Observable.create(
                new ChainMapper(traceChainId, vt, chainMap)).toBlocking.first

            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            mapper.completeTraceChain()

            subscriber.assertTerminalEvent()

            intercept[NotFoundException] {
                Observable.create(
                    new ChainMapper(traceChainId, vt, chainMap)).toBlocking.first
            }
        }

        scenario("trace chain mapper doesn't emit chain if no request enabled") {
            val bridge = newBridge("bridge0")
            val traceId = UUID.randomUUID

            val tr = TraceRequest.newBuilder.setId(traceId)
                .setNetworkId(bridge)
                .setEnabled(true)
                .setCondition(Condition.newBuilder
                                  .setNwProto(123))
                .build()
            vt.store.create(tr)

            val subscriber = new TestObserver[Option[UUID]]
            val mapper = new TraceRequestTestMapper(bridge, vt, chainMap)
            mapper.subscribe(subscriber)

            mapper.requestForBridge()

            val traceChainId = subscriber.getOnNextEvents.get(0).get
            var chainObj = Observable.create(
                new ChainMapper(traceChainId, vt, chainMap)).toBlocking.first
            chainObj.rules.size shouldBe 1
            vt.store.update(tr.toBuilder.setEnabled(false).build())
            intercept[NotFoundException] {
                Observable.create(
                    new ChainMapper(traceChainId, vt, chainMap)).toBlocking.first
            }

            vt.store.update(tr.toBuilder.setEnabled(true).build())
            chainObj = Observable.create(
                new ChainMapper(traceChainId, vt, chainMap)).toBlocking.first
            chainObj.rules.size shouldBe 1
        }
    }

    feature("trace request mapper works with each device type") {
        scenario("with port") {
            val bridge = newBridge("bridge0")
            val portId = newBridgePort(bridge)
            val chain = newInboundChainOnPort("chain0", portId)
            val chain2 = newChain("chain1")
            val traceId = UUID.randomUUID

            val tr = TraceRequest.newBuilder.setId(traceId)
                                .setPortId(portId)
                                .setEnabled(false)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build()
            vt.store.create(tr)
            val portSubscriber = new TestSubscriber[SimPort]
            val mapper = new PortMapper(portId, vt, chainMap)
            mapper.call(portSubscriber)

            portSubscriber.getOnNextEvents.size shouldBe 1
            var port = portSubscriber.getOnNextEvents.get(0)
            port.inboundFilter shouldBe chain

            vt.store.update(tr.toBuilder.setEnabled(true).build)
            portSubscriber.getOnNextEvents.size shouldBe 2
            port = portSubscriber.getOnNextEvents.get(1)
            port.inboundFilter should not be (chain)
            var chainObj = chainMap.get(port.inboundFilter)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            val traceChainId = port.inboundFilter
            // update to inbound filter is updated in trace chain
            val topPort = Await.result(vt.store.get(classOf[Port], portId),
                                       5 seconds)
            vt.store.update(topPort.toBuilder.setInboundFilterId(chain2).build())

            portSubscriber.getOnNextEvents.size shouldBe 3
            port = portSubscriber.getOnNextEvents.get(2)
            port.inboundFilter shouldBe traceChainId
            chainObj = chainMap.get(port.inboundFilter)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain2
                case _ => fail("Should have been a jump rule")
            }
            vt.store.update(tr.toBuilder.setEnabled(false).build())
            portSubscriber.getOnNextEvents.size shouldBe 4
            port = portSubscriber.getOnNextEvents.get(3)
            port.inboundFilter shouldBe chain2
        }

        scenario("with bridge") {
            val bridgeId = newBridge("bridge0")
            val chain = newInboundChainOnBridge("chain0", bridgeId)
            val chain2 = newChain("chain1")
            val traceId = UUID.randomUUID

            val tr = TraceRequest.newBuilder.setId(traceId)
                                .setNetworkId(bridgeId)
                                .setEnabled(false)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build()
            vt.store.create(tr)
            val bridgeSubscriber = new TestSubscriber[SimBridge]
            val mapper = new BridgeMapper(bridgeId, vt, chainMap)
            mapper.call(bridgeSubscriber)

            bridgeSubscriber.getOnNextEvents.size shouldBe 1
            var bridge = bridgeSubscriber.getOnNextEvents.get(0)
            bridge.inFilterId shouldBe Some(chain)

            vt.store.update(tr.toBuilder.setEnabled(true).build)
            bridgeSubscriber.getOnNextEvents.size shouldBe 2
            bridge = bridgeSubscriber.getOnNextEvents.get(1)
            bridge.inFilterId should not be Some(chain)
            var chainObj = chainMap.get(bridge.inFilterId.get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            val traceChainId = bridge.inFilterId
            // update to inbound filter is updated in trace chain
            val topBridge = Await.result(vt.store.get(classOf[Network], bridgeId),
                                       5 seconds)
            vt.store.update(topBridge.toBuilder.setInboundFilterId(chain2).build())

            bridgeSubscriber.getOnNextEvents.size shouldBe 3
            bridge = bridgeSubscriber.getOnNextEvents.get(2)
            bridge.inFilterId shouldBe traceChainId
            chainObj = chainMap.get(bridge.inFilterId.get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain2
                case _ => fail("Should have been a jump rule")
            }
            vt.store.update(tr.toBuilder.setEnabled(false).build())
            bridgeSubscriber.getOnNextEvents.size shouldBe 4
            bridge = bridgeSubscriber.getOnNextEvents.get(3)
            bridge.inFilterId shouldBe Some(chain2)
        }

        scenario("with router") {
            val routerId = newRouter("router0")
            val chain = newInboundChainOnRouter("chain0", routerId)
            val chain2 = newChain("chain1")
            val traceId = UUID.randomUUID

            val tr = TraceRequest.newBuilder.setId(traceId)
                                .setRouterId(routerId)
                                .setEnabled(false)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build()
            vt.store.create(tr)
            val routerSubscriber = new TestSubscriber[SimRouter]
            val mapper = new RouterMapper(routerId, vt, chainMap)
            mapper.call(routerSubscriber)

            routerSubscriber.getOnNextEvents.size shouldBe 1
            var router = routerSubscriber.getOnNextEvents.get(0)
            router.cfg.inboundFilter shouldBe chain

            vt.store.update(tr.toBuilder.setEnabled(true).build)
            routerSubscriber.getOnNextEvents.size shouldBe 2
            router = routerSubscriber.getOnNextEvents.get(1)
            router.cfg.inboundFilter should not be chain
            var chainObj = chainMap.get(router.cfg.inboundFilter)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            val traceChainId = router.cfg.inboundFilter
            // update to inbound filter is updated in trace chain
            val topRouter = Await.result(vt.store.get(classOf[Router], routerId),
                                         5 seconds)
            vt.store.update(topRouter.toBuilder.setInboundFilterId(chain2).build())
            routerSubscriber.getOnNextEvents.size shouldBe 3
            router = routerSubscriber.getOnNextEvents.get(2)
            router.cfg.inboundFilter shouldBe traceChainId
            chainObj = chainMap.get(router.cfg.inboundFilter)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain2
                case _ => fail("Should have been a jump rule")
            }
            vt.store.update(tr.toBuilder.setEnabled(false).build())
            routerSubscriber.getOnNextEvents.size shouldBe 4
            router = routerSubscriber.getOnNextEvents.get(3)
            router.cfg.inboundFilter shouldBe chain2
        }
    }

    feature("trace request chain mapper") {
        scenario("mapper emits trace chain") {
            val bridge = newBridge("bridge0")
            val chain = newInboundChainOnBridge("chain0", bridge)
            val traceId = UUID.randomUUID

            vt.store.create(TraceRequest.newBuilder.setId(traceId)
                                .setNetworkId(bridge)
                                .setEnabled(true)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build())

            val mapper = new TraceRequestTestMapper(bridge, vt, chainMap)
            val subscriber = new TestObserver[Option[UUID]]
            mapper.subscribe(subscriber)
            mapper.requestForBridge()
            val chainId = subscriber.getOnNextEvents.get(0).get
            val chainObj = chainMap.get(chainId).get.toBlocking.first

            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }
        }

        scenario("mapper emits new chain when rule enabled/disabled") {
            val bridge = newBridge("bridge0")
            val chain = newInboundChainOnBridge("chain0", bridge)
            val traceId = UUID.randomUUID

            val tr = TraceRequest.newBuilder.setId(traceId)
                                .setNetworkId(bridge)
                                .setEnabled(false)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build()
            vt.store.create(tr)

            val mapper = new TraceRequestTestMapper(bridge, vt, chainMap)
            val subscriber = new TestObserver[Option[UUID]]()
            mapper.subscribe(subscriber)
            mapper.requestForBridge()

            subscriber.getOnNextEvents.size shouldBe 1
            subscriber.getOnNextEvents.get(0) shouldBe Some(chain)
            chainMap.size shouldBe 0

            vt.store.update(tr.toBuilder.setEnabled(true).build())
            subscriber.getOnNextEvents.size shouldBe 2
            val chainObj = chainMap.get(subscriber.getOnNextEvents.get(1).get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }
            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            vt.store.update(tr.toBuilder.setEnabled(false).build())
            subscriber.getOnNextEvents.size shouldBe 3
            subscriber.getOnNextEvents.get(2) shouldBe Some(chain)
            chainMap.size shouldBe 0
        }

        scenario("device has no existing chain, then adds one") {
            val bridge = newBridge("bridge0")
            val traceId = UUID.randomUUID

            val tr = TraceRequest.newBuilder.setId(traceId)
                                .setNetworkId(bridge)
                                .setEnabled(true)
                                .setCondition(Condition.newBuilder
                                                  .setNwProto(123))
                                .build()
            vt.store.create(tr)

            val mapper = new TraceRequestTestMapper(bridge, vt, chainMap)
            val subscriber = new TestObserver[Option[UUID]]()
            mapper.subscribe(subscriber)
            mapper.requestForBridge()

            subscriber.getOnNextEvents.size shouldBe 1
            var chainObj = chainMap.get(subscriber.getOnNextEvents.get(0).get)
                .get.toBlocking.first

            chainObj.rules.size shouldBe 1
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }

            // add an infilter chain to bridge
            val chain = newInboundChainOnBridge("chain0", bridge)
            mapper.requestForBridge()
            subscriber.getOnNextEvents.size shouldBe 2
            chainObj = chainMap.get(subscriber.getOnNextEvents.get(1).get)
                .get.toBlocking.first

            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }
            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            // disable the trace request
            vt.store.update(tr.toBuilder.setEnabled(false).build())
            subscriber.getOnNextEvents.size shouldBe 3
            subscriber.getOnNextEvents.get(2) shouldBe Some(chain)
            chainMap.size shouldBe 0

            // reenable the trace request
            vt.store.update(tr.toBuilder.setEnabled(true).build())
            subscriber.getOnNextEvents.size shouldBe 4
            chainObj = chainMap.get(subscriber.getOnNextEvents.get(3).get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 2
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }
            chainObj.rules.get(1) match {
                case r: JumpRule =>
                    r.jumpToChainID shouldBe chain
                case _ => fail("Should have been a jump rule")
            }

            // remove the infilter from the bridge
            val brObj = Await.result(vt.store.get(classOf[Network], bridge),
                                     5 seconds)
            store.update(brObj.toBuilder.clearInboundFilterId.build())
            mapper.requestForBridge()
            subscriber.getOnNextEvents.size shouldBe 5
            chainObj = chainMap.get(subscriber.getOnNextEvents.get(4).get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 1
            chainObj.rules.get(0) match {
                case r: TraceRule =>
                    r.getRequestId shouldBe traceId
                    r.getCondition.nwProto shouldBe 123
                case _ => fail("should have been trace rule")
            }
        }

        scenario("Multiple traces on a single device, all enabled") {
            val bridge = newBridge("bridge0")
            val chain = newInboundChainOnBridge("chain0", bridge)

            val tr0 = TraceRequest.newBuilder.setId(UUID.randomUUID)
                .setNetworkId(bridge)
                .setEnabled(true)
                .setCondition(Condition.newBuilder
                                  .setNwProto(123))
                .build()
            val tr1 = TraceRequest.newBuilder.setId(UUID.randomUUID)
                .setNetworkId(bridge)
                .setEnabled(true)
                .setCondition(Condition.newBuilder
                                  .setNwProto(456))
                .build()
            val tr2 = TraceRequest.newBuilder.setId(UUID.randomUUID)
                .setNetworkId(bridge)
                .setEnabled(true)
                .setCondition(Condition.newBuilder
                                  .setNwProto(678))
                .build()

            vt.store.create(tr0)
            vt.store.create(tr1)
            vt.store.create(tr2)

            val mapper = new TraceRequestTestMapper(bridge, vt, chainMap)
            val subscriber = new TestObserver[Option[UUID]]()
            mapper.subscribe(subscriber)
            mapper.requestForBridge()

            subscriber.getOnNextEvents.size shouldBe 1
            var chainObj = chainMap.get(subscriber.getOnNextEvents.get(0).get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 4

            var expected = mutable.Set(tr0.getId.asJava, tr1.getId.asJava, tr2.getId.asJava)
            for (rule <- chainObj.rules.asScala) {
                rule match {
                    case r: TraceRule => {
                        expected should contain (r.getRequestId)
                        expected -= r.getRequestId
                    }
                    case j: JumpRule => j.jumpToChainID shouldBe chain
                    case _ => fail("Unexpected")
                }
            }
            expected.size shouldBe 0

            // disable one trace
            vt.store.update(tr1.toBuilder.setEnabled(false).build)
            subscriber.getOnNextEvents.size shouldBe 2
            chainObj = chainMap.get(subscriber.getOnNextEvents.get(1).get)
                .get.toBlocking.first
            chainObj.rules.size shouldBe 3

            expected = mutable.Set(tr0.getId.asJava, tr2.getId.asJava)
            for (rule <- chainObj.rules.asScala) {
                rule match {
                    case r: TraceRule => {
                        expected should contain (r.getRequestId)
                        expected -= r.getRequestId
                    }
                    case j: JumpRule => j.jumpToChainID shouldBe chain
                    case _ => fail("Unexpected")
                }
            }
            expected.size shouldBe 0
        }
    }
}

class TraceRequestTestMapper[D <: VirtualDevice](deviceId: UUID,
                                                 vt: VirtualTopology,
                                                 _traceChainMap: mutable.Map[UUID,Subject[SimChain,SimChain]])
                            (implicit tag: ClassTag[D])
        extends DeviceWithChainsMapper[D](deviceId, vt)
        with TraceRequestChainMapper[D] {

    override def traceChainMap: mutable.Map[UUID,Subject[SimChain,SimChain]] = _traceChainMap

    def requestForBridge(): Unit = {
        val bridge = Await.result(vt.store.get(classOf[Network], deviceId),
                                  5 seconds)
        requestTraceChain(if (bridge.hasInboundFilterId) {
                              Some(bridge.getInboundFilterId)
                          } else {
                              None
                          }, bridge.getTraceRequestIdsList.asScala.toList.map(_.asJava))
    }

    def requestForPort(): Unit = {
        val port = Await.result(vt.store.get(classOf[Port], deviceId),
                                5 seconds)
        requestTraceChain(if (port.hasInboundFilterId) {
                              Some(port.getInboundFilterId)
                          } else {
                              None
                          }, port.getTraceRequestIdsList.asScala.toList.map(_.asJava))
    }

    def requestForRouter(): Unit = {
        val router = Await.result(vt.store.get(classOf[Router], deviceId),
                                  5 seconds)
        requestTraceChain(if (router.hasInboundFilterId) {
                              Some(router.getInboundFilterId)
                          } else {
                              None
                          }, router.getTraceRequestIdsList.asScala.toList.map(_.asJava))
    }

    def subscribe(observer: Observer[Option[UUID]]): Unit =
        traceChainObservable.subscribe(observer)

    override def observable: Observable[D] = ???
}

