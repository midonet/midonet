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

package org.midonet.cluster.services.vxgw

import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.services.MidonetBackend.{AliveKey, FloodingProxyKey}
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.test.util.ZookeeperTestSuite
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPAddressUtil.toProto
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.conf.MidoTestConfigurator
import org.midonet.packets.IPv4Addr
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class FloodingProxyManagerTest extends FlatSpec with Matchers
                                                with BeforeAndAfter
                                                with BeforeAndAfterAll
                                                with GivenWhenThen
                                                with TopologyBuilder
                                                with ZookeeperTestSuite
                                                with MidonetEventually {

    // Testing this with a real ZK as the coverage in upper layers is very low
    // so we reduce likelyhood of missing errors due to the in-memory zoom
    // version.

    var backend: MidonetBackend = _
    var fpManager: FloodingProxyManager = _
    var obs: TestAwaitableObserver[FloodingProxy] = _

    val timeout = 10 second

    val backendCfg = new MidonetBackendConfig(config)
    override protected def config = MidoTestConfigurator.forClusters(
        ConfigFactory.parseString(s"""
           |zookeeper.zookeeper_hosts : "127.0.0.1:$ZK_PORT"
           |zookeeper.root_key : "/test-${UUID.randomUUID}"
        """.stripMargin))

    before {
        zkClient.create().creatingParentsIfNeeded().forPath(backendCfg.rootKey)
        backend = new MidonetBackendService(backendCfg, zkClient,
                                            metricRegistry = null)
        backend.setupBindings()
        fpManager = new FloodingProxyManager(backend)
        obs = new TestAwaitableObserver[FloodingProxy]
        fpManager.herald.observable.subscribe(obs)
    }

    after {
        fpManager.stop()
        zkClient.delete().deletingChildrenIfNeeded().forPath(backendCfg.rootKey)
    }

    override def afterAll() {
         zkClient.close()
    }

    private def currentFp(tzId: UUID): Option[FloodingProxy] = {
        backend.stateStore
               .getKey(classOf[TunnelZone], tzId, FloodingProxyKey)
               .toBlocking.single() match {
                   case SingleValueKey(_, v, _) =>
                       v.map(FloodingProxyHerald.deserialize(tzId, _))
                   case _ => None
               }
    }

    "The happy case" should "yield happy results" in {
        fpManager.start()

        val tzId = makeTz()
        val ip1 = IPv4Addr.random
        val h1Id = testOnOneHost(tzId, ip1, 10)

        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0).hostId shouldBe h1Id
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip1
        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tzId
        ensureCurrentFpIs(tzId, h1Id, ip1)

        toggleAlive(h1Id, isAlive = false)

        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1).hostId shouldBe null
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null
        obs.getOnNextEvents.get(1).tunnelZoneId shouldBe tzId
        ensureCurrentFpIs(tzId, null, null)

        val ip2 = IPv4Addr.random
        val h2Id = testOnOneHost(tzId, ip2, 1000)

        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2).hostId shouldBe h2Id
        obs.getOnNextEvents.get(2).tunnelIp shouldBe ip2
        obs.getOnNextEvents.get(2).tunnelZoneId shouldBe tzId
        ensureCurrentFpIs(tzId, h2Id, ip2)

        toggleAlive(h1Id, isAlive = true)

        backend.store.delete(classOf[Host], h2Id)

        // events might come in different order so FP might transition to
        // delete -> then 1, or to 1 straight, so the notifications are not
        // deterministic
        eventually {
            ensureCurrentFpIs(tzId, h1Id, ip1)
        }
    }

    "Zk hiccups" should "not kill the manager" in {

        fpManager.start()

        val tzId = makeTz()
        val tunIp = IPv4Addr.random
        val hId = testOnOneHost(tzId, tunIp)

        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0).tunnelIp shouldBe tunIp

        zkServer.stop()
        Thread.sleep(2000)
        zkServer.restart()

        // And things continue working as normal

        toggleAlive(hId, isAlive = false)

        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null

        toggleAlive(hId, isAlive = true)

        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2).tunnelIp shouldBe tunIp
    }

    "Tunnel zone deletions" should "clear their flooding proxy" in {
        fpManager.start()
        val tzId = makeTz()
        val ip = IPv4Addr.random
        val hId = testOnOneHost(tzId, ip)

        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip
        ensureCurrentFpIs(tzId, hId, ip)

        backend.store.delete(classOf[TunnelZone], tzId)

        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null
        ensureCurrentFpIs(tzId, null, null)
    }

    "Host deletions" should "release the Flooding Proxy changes" in {
        fpManager.start()
        val tzId = makeTz()
        val ip = IPv4Addr.random
        val hId = testOnOneHost(tzId, ip)

        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip
        ensureCurrentFpIs(tzId, hId, ip)

        backend.store.delete(classOf[Host], hId)

        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null
        ensureCurrentFpIs(tzId, null, null)
    }

    "A stop" should "makes the manager ignore updates" in {

        fpManager.start()

        val tzId = makeTz()
        val tunIp1 = IPv4Addr.fromString("10.0.0.0")// IPv4Addr.random
        val hId1 = testOnOneHost(tzId, tunIp1)

        obs.awaitOnNext(1, timeout)

        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tzId
        obs.getOnNextEvents.get(0).tunnelIp shouldBe tunIp1
        obs.getOnNextEvents.get(0).hostId shouldBe hId1
        ensureCurrentFpIs(tzId, hId1, tunIp1)

        fpManager.stop()

        // Creating a new host is inocuous, the manager is not watching
        val tunIp2 = tunIp1.next     // helpful for debugging
        testOnOneHost(tzId, tunIp2, 1000, assertTransition = false)

        ensureCurrentFpIs(tzId, hId1, tunIp1)
        obs.getOnNextEvents should have size 1 // no change

        // Also deleting the original one
        backend.store.delete(classOf[Host], hId1)

        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tzId
        obs.getOnNextEvents.get(0).tunnelIp shouldBe tunIp1
        obs.getOnNextEvents.get(0).hostId shouldBe hId1
        ensureCurrentFpIs(tzId, hId1, tunIp1)
    }

    private def makeTz(): UUID = {
        val tz = createTunnelZone(UUID.randomUUID(), TunnelZone.Type.VTEP)
        val tzId = fromProto(tz.getId)
        backend.store.create(tz)
        tzId
    }

    private def testOnOneHost(tzId: UUID, tunIp: IPv4Addr,
                              weight: Int = 1,
                              assertTransition: Boolean = true): UUID = {
        val h = createHost().toBuilder.setFloodingProxyWeight(weight)
                                      .build()
        backend.store.create(h)

        // Add it to the tunnel zone
        val tz = backend.store.get(classOf[TunnelZone], tzId).await()
        val newTz = tz.toBuilder.addHosts(HostToIp.newBuilder()
                                          .setHostId(h.getId)
                                          .setIp(toProto(tunIp))
                                          .build())
                                .addHostIds(h.getId)
                                .build()

        backend.store.update(newTz)

        val hId = fromProto(h.getId)
        toggleAlive(hId)
        hId
    }

    private def toggleAlive(id: UUID, isAlive: Boolean = true) = {
        if (isAlive) {
            backend.stateStore.addValue(classOf[Host], id, AliveKey, AliveKey)
                              .toBlocking.first()
        } else {
            backend.stateStore.removeValue(classOf[Host], id, AliveKey, AliveKey)
                              .toBlocking.first()
        }
    }

    private def ensureCurrentFpIs(tzId: UUID, hostId: UUID,
                                  tunIp: IPv4Addr): Unit = {
        if (hostId == null) {
            currentFp(tzId) shouldBe None
        } else {
            currentFp(tzId) shouldBe Option(FloodingProxy(tzId, hostId, tunIp))
        }
    }
}
