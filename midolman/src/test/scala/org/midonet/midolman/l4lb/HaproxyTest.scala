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
package org.midonet.midolman.l4lb

import java.util.UUID

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.testkit.{TestActorRef, TestKit}

import org.junit.runner.RunWith
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.packets.IPv4Addr
import org.mockito.Mockito.{mock, verify, times}
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.SpanSugar

import org.midonet.cluster.models.Topology.{HealthMonitor => topHM, Router, Pool => topPool, Host}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.{HealthMonitor => HMSystem}


@RunWith(classOf[JUnitRunner])
class HaproxyTest extends TestKit(ActorSystem("HealthMonitorConfigWatcherTest"))
        with MidolmanSpec
        with TopologyBuilder
        with ShouldMatchers
        with SpanSugar
        with Eventually {

    var hmSystem: ActorRef = _
    var backend: MidonetBackend = _
    var conf = mock(classOf[MidolmanConfig])

    def makeRouter(): UUID = {
        val rid = UUID.randomUUID()
        val r = createRouter(id = rid,
                             adminStateUp = true)
        backend.store.create(r)
        rid
    }

    def makeLB(routerId: UUID): UUID = {
        val lbId = UUID.randomUUID()
        val lb = createLoadBalancer(id = lbId,
                                    adminStateUp = Some(true),
                                    routerId = Some(routerId))
        backend.store.create(lb)
        lbId
    }

    def makeHM(delay: Int, timeout: Int, retries: Int): UUID = {
        val hmId = UUID.randomUUID()
        val hm = createHealthMonitor(id = hmId,
                                     adminStateUp = true,
                                     delay = Some(delay),
                                     timeout = Some(timeout),
                                     maxRetries = Some(retries))
        backend.store.create(hm)
        hmId
    }

    def makeVip(port: Int, addr: String): UUID = {
        val vipId = UUID.randomUUID()
        val v = createVip(id = vipId,
                          adminStateUp = Some(true),
                          address = Some(IPv4Addr.fromString(addr)),
                          protocolPort = Some(port))
        backend.store.create(v)
        vipId
    }

    def makePool(hmId: UUID, lbId: UUID, vipId: UUID): UUID = {
        val poolId = UUID.randomUUID()
        val p = createPool(id = poolId,
                           adminStateUp = Some(true),
                           healthMonitorId = Some(hmId),
                           loadBalancerId = Some(lbId),
                           vipId = Some(vipId))
        backend.store.create(p)
        poolId
    }

    def getRouter(routerId: UUID): Router = {
        backend.store.get(classOf[Router], routerId).value.get.get
    }

    class TestableHealthMonitor extends HMSystem(conf, backend, null) {
        override def getHostId = hostId

        override def startChildHaproxyMonitor(poolId: UUID, config: PoolConfig,
                                     routerId: UUID) = {
            val poolUpdater = new PoolUpdater(backend.store, maxRetries = 0)
            context.actorOf(
                Props(
                    new HaproxyHealthMonitor(config, self, routerId, store,
                                             hostId, poolUpdater) {
                        override def writeConf(config: PoolConfig): Unit = {}
                    }
                ).withDispatcher(context.props.dispatcher),
                config.id.toString)
        }
    }

    override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])
        conf = injector.getInstance(classOf[MidolmanConfig])
        HMSystem.ipCommand = mock(classOf[IP])
        hmSystem = TestActorRef(Props(new TestableHealthMonitor()))(actorSystem)
    }

    override def afterTest(): Unit = {
        actorSystem.stop(hmSystem)
    }

    feature("Single health monitor on a router") {
        scenario("router ports and routes are created and cleaned up") {

            val to = timeout(10 seconds)

            val routerId = makeRouter()

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 0 }

            val lbId = makeLB(routerId)
            val hmId = makeHM(2, 2, 2)
            val vipId = makeVip(80, "10.0.0.1")
            val poolId = makePool(hmId, lbId, vipId)


            val poolName = poolId.toString.substring(0, 8) + "_hm"

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 1 }

            /* TODO: find out why the mapper sends 3 notifications.
             * This is not expected: a single pool-health monitor config
             * should result in a single mapper notification.
             */
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName)

            backend.store.delete(classOf[topPool], poolId)
            backend.store.delete(classOf[topHM], hmId)

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 0 }

            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName)
        }
        scenario("ports and routes are created on separate routers") {

            val to = timeout(10 seconds)

            val routerId1 = makeRouter()

            val routerId2 = makeRouter()

            eventually(to) { getRouter(routerId1).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId1).getRouteIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId2).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId2).getRouteIdsCount shouldBe 0 }


            val lbId1 = makeLB(routerId1)
            val hmId1 = makeHM(2, 2, 2)
            val vipId1 = makeVip(80, "10.0.0.1")
            val poolId1 = makePool(hmId1, lbId1, vipId1)

            eventually(to) { getRouter(routerId1).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId1).getRouteIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId2).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId2).getRouteIdsCount shouldBe 0 }

            val poolName1 = poolId1.toString.substring(0, 8) + "_hm"

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)

            val lbId2 = makeLB(routerId2)
            val hmId2 = makeHM(2, 2, 2)
            val vipId2 = makeVip(80, "10.0.0.1")
            val poolId2 = makePool(hmId2, lbId2, vipId2)

            val poolName2 = poolId2.toString.substring(0, 8) + "_hm"

            eventually(to) { getRouter(routerId1).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId1).getRouteIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId2).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId2).getRouteIdsCount shouldBe 1 }

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName2)

            backend.store.delete(classOf[topPool], poolId1)
            backend.store.delete(classOf[topHM], hmId1)

            eventually(to) { getRouter(routerId1).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId1).getRouteIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId2).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId2).getRouteIdsCount shouldBe 1 }

            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName1)
            verify(HMSystem.ipCommand, times(2)).namespaceExist(poolName2)

            backend.store.delete(classOf[topPool], poolId2)
            backend.store.delete(classOf[topHM], hmId2)

            eventually(to) { getRouter(routerId1).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId1).getRouteIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId2).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId2).getRouteIdsCount shouldBe 0 }

            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName1)
            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName2)
        }
    }

    feature("Two health monitor on a router") {
        scenario("router ports and routes are created and cleaned up") {
            val to = timeout(10 seconds)

            val routerId = makeRouter()

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 0 }

            val lbId = makeLB(routerId)
            val hmId1 = makeHM(2, 2, 2)
            val vipId1 = makeVip(80, "10.0.0.1")
            val poolId1 = makePool(hmId1, lbId, vipId1)

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 1 }

            val poolName1 = poolId1.toString.substring(0, 8) + "_hm"
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)

            val hmId2 = makeHM(2, 2, 2)
            val vipId2 = makeVip(80, "10.0.0.2")
            val poolId2 = makePool(hmId2, lbId, vipId2)

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 2 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 2 }

            val poolName2 = poolId2.toString.substring(0, 8) + "_hm"
            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(1)).ensureNamespace(poolName2)

            backend.store.delete(classOf[topPool], poolId1)
            backend.store.delete(classOf[topHM], hmId1)

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 1 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 1 }

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(1)).ensureNamespace(poolName2)
            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName1)
            verify(HMSystem.ipCommand, times(0)).namespaceExist(poolName2)

            backend.store.delete(classOf[topPool], poolId2)
            backend.store.delete(classOf[topHM], hmId2)

            eventually(to) { getRouter(routerId).getPortIdsCount shouldBe 0 }
            eventually(to) { getRouter(routerId).getRouteIdsCount shouldBe 0 }

            verify(HMSystem.ipCommand, times(3)).ensureNamespace(poolName1)
            verify(HMSystem.ipCommand, times(1)).ensureNamespace(poolName2)
            verify(HMSystem.ipCommand, times(3)).namespaceExist(poolName1)
            verify(HMSystem.ipCommand, times(1)).namespaceExist(poolName2)
        }
    }
}