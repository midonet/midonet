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
import java.util.concurrent.TimeoutException

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher
import org.midonet.cluster.data.storage.{ZookeeperObjectMapper, SingleValueKey}
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
    // so we reduce likelihood of missing errors due to the in-memory zoom
    // version.

    var backend: MidonetBackend = _
    var connectionWatcher: ZkConnectionAwareWatcher = _
    var fpManager: FloodingProxyManager = _
    var obs: TestAwaitableObserver[FloodingProxy] = _

    val timeout = 1 second

    val backendCfg = new MidonetBackendConfig(config)
    override protected def config = MidoTestConfigurator.forClusters(
        ConfigFactory.parseString(s"""
           |zookeeper.zookeeper_hosts : "127.0.0.1:$ZK_PORT"
           |zookeeper.root_key : "/test-${UUID.randomUUID}"
        """.stripMargin))

    before {
        MidonetBackend.isCluster = true

        zkClient.create().creatingParentsIfNeeded().forPath(backendCfg.rootKey)
        backend = new MidonetBackendService(backendCfg, zkClient,
                                            metricRegistry = null)
        backend.startAsync().awaitRunning()
        fpManager = new FloodingProxyManager(backend)
        obs = new TestAwaitableObserver[FloodingProxy]
        fpManager.herald.observable.subscribe(obs)
    }

    after {
        fpManager.stop()
        zkClient.delete().deletingChildrenIfNeeded().forPath(backendCfg.rootKey)
        backend.stopAsync().awaitTerminated()
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

        val tzId = makeTz(TunnelZone.Type.VTEP)

        val ip1 = IPv4Addr.random
        val h1Id = addHostToZone(tzId, ip1, 10)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0).hostId shouldBe h1Id
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip1
        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tzId
        ensureCurrentFpIs(tzId, h1Id, ip1)

        toggleAlive(h1Id, isAlive = false)

        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1).hostId shouldBe null
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null
        obs.getOnNextEvents.get(1).tunnelZoneId shouldBe tzId
        ensureCurrentFpIs(tzId, null, null)

        val ip2 = IPv4Addr.random
        val h2Id = addHostToZone(tzId, ip2, 1000)

        obs.awaitOnNext(3, timeout) shouldBe true
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

    "Filtering by host id" should "observe only that host's tunnel zones" in {
        fpManager.start()

        val tz1Id = makeTz(TunnelZone.Type.VTEP)
        val ip1 = IPv4Addr.random
        val h1Id = addHostToZone(tz1Id, ip1, 10)

        val herald = new FloodingProxyHerald(backend, Some(h1Id))
        herald.start()
        val obs =  new TestAwaitableObserver[FloodingProxy]
        herald.observable.subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0).hostId shouldBe h1Id
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip1
        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tz1Id
        herald.lookup(tz1Id) should be (Some(FloodingProxy(tz1Id, h1Id, ip1)))

        val tz2Id = makeTz(TunnelZone.Type.VTEP)
        val ip2 = IPv4Addr.random
        addHostToZone(tz2Id, ip2, 1000)

        intercept[TimeoutException] {
            obs.awaitOnNext(2, timeout)
        }
        herald.lookup(tz2Id) should be (None)
    }

     "Multiple tunnel zones" should "notify of their respective FPs" in {
        fpManager.start()

        val tz1Id = makeTz(TunnelZone.Type.VTEP)
        val ip1 = IPv4Addr.random
        val h1Id = addHostToZone(tz1Id, ip1, 10)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0).hostId shouldBe h1Id
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip1
        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tz1Id
        ensureCurrentFpIs(tz1Id, h1Id, ip1)

        val tz2Id = makeTz(TunnelZone.Type.VTEP)
        val ip2 = IPv4Addr.random
        val h2Id = addHostToZone(tz2Id, ip2, 1000)

        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1).hostId shouldBe h2Id
        obs.getOnNextEvents.get(1).tunnelIp shouldBe ip2
        obs.getOnNextEvents.get(1).tunnelZoneId shouldBe tz2Id
        ensureCurrentFpIs(tz2Id, h2Id, ip2)
    }

    "Tunnel Zones that are not type VTEP" should "not generate any FPs" in {
        fpManager.start()

        val tzVxlan = makeTz(TunnelZone.Type.VXLAN)
        val tzGre = makeTz(TunnelZone.Type.GRE)
        val tunIpVx = IPv4Addr.random
        val tunIpGre = IPv4Addr.random

        addHostToZone(tzVxlan, tunIpVx)
        addHostToZone(tzGre, tunIpGre)

        obs.getOnNextEvents shouldBe empty
    }

    "Zk hiccups" should "not kill the manager" in {

        fpManager.start()

        val tzId = makeTz(TunnelZone.Type.VTEP)
        val tunIp = IPv4Addr.random
        val hId = addHostToZone(tzId, tunIp)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0).tunnelIp shouldBe tunIp

        zkServer.stop()
        Thread.sleep(2000)
        zkServer.restart()

        // And things continue working as normal

        toggleAlive(hId, isAlive = false)

        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null

        toggleAlive(hId, isAlive = true)

        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2).tunnelIp shouldBe tunIp
    }

    "Tunnel zone deletions" should "clear their flooding proxy" in {
        fpManager.start()
        val tzId = makeTz(TunnelZone.Type.VTEP)
        val ip = IPv4Addr.random
        val hId = addHostToZone(tzId, ip)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip
        ensureCurrentFpIs(tzId, hId, ip)

        backend.store.delete(classOf[TunnelZone], tzId)

        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null
        eventually { ensureCurrentFpIs(tzId, null, null) }
    }

    "Host deletions" should "release the Flooding Proxy changes" in {
        fpManager.start()
        val tzId = makeTz(TunnelZone.Type.VTEP)
        val ip = IPv4Addr.random
        val hId = addHostToZone(tzId, ip)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0).tunnelIp shouldBe ip
        ensureCurrentFpIs(tzId, hId, ip)

        backend.store.delete(classOf[Host], hId)

        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1).tunnelIp shouldBe null
        ensureCurrentFpIs(tzId, null, null)
    }

    "A stop" should "makes the manager ignore updates" in {

        fpManager.start()

        val tzId = makeTz(TunnelZone.Type.VTEP)
        val tunIp1 = IPv4Addr.fromString("10.0.0.0")// IPv4Addr.random
        val hId1 = addHostToZone(tzId, tunIp1)

        obs.awaitOnNext(1, timeout) shouldBe true

        obs.getOnNextEvents.get(0).tunnelZoneId shouldBe tzId
        obs.getOnNextEvents.get(0).tunnelIp shouldBe tunIp1
        obs.getOnNextEvents.get(0).hostId shouldBe hId1
        ensureCurrentFpIs(tzId, hId1, tunIp1)

        fpManager.stop()

        // Creating a new host is inocuous, the manager is not watching
        val tunIp2 = tunIp1.next     // helpful for debugging
        addHostToZone(tzId, tunIp2, 1000)

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

    private def makeTz(ofType: TunnelZone.Type): UUID = {
        val tz = createTunnelZone(UUID.randomUUID(), ofType)
        val tzId = fromProto(tz.getId)
        backend.store.create(tz)
        tzId
    }

    private def addHostToZone(tzId: UUID, tunIp: IPv4Addr,
                              weight: Int = 1): UUID = {
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
        // Create a private store with the host ID as namespace.
        val hostStore =
            new ZookeeperObjectMapper(backendCfg.rootKey + "/zoom", id.toString,
                                      backend.curator, backend.reactor,
                                      backend.connection, backend.connectionWatcher)
        MidonetBackend.setupBindings(hostStore, hostStore)
        if (isAlive) {
            hostStore.addValue(classOf[Host], id, AliveKey, AliveKey)
                     .toBlocking.first()
        } else {
            hostStore.removeValue(classOf[Host], id, AliveKey, AliveKey)
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
