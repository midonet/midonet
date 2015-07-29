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
import org.midonet.cluster.util.{UUIDUtil, IPAddressUtil}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.conf.MidoTestConfigurator
import org.midonet.packets.IPv4Addr
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

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
    var backendCfg: MidonetBackendConfig = _
    var fpManager: FloodingProxyManager = _

    override protected def config = MidoTestConfigurator.forClusters(
        ConfigFactory.parseString(s"""
           |zookeeper.zookeeper_hosts : "127.0.0.1:$ZK_PORT"
           |zookeeper.root_key : "/test-${UUID.randomUUID}"
        """.stripMargin))

    before {
        backendCfg = new MidonetBackendConfig(config)
        zkClient.create().creatingParentsIfNeeded().forPath(backendCfg.rootKey)
        backend = new MidonetBackendService(backendCfg, zkClient)
        backend.setupBindings()
        fpManager = new FloodingProxyManager(backend)
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
                       v.map(FloodingProxyHerald.deserialize)
                   case _ => None
               }
    }

    "The happy case" should "yield happy results" in {
        fpManager.start()

        When("A tz of type VTEP is created")
        val tzId = makeTz()
        val h1Id = testOnOneHost(tzId, IPv4Addr.random, 10)

        When("The host goes down")
        toggleAlive(h1Id, isAlive = false)

        Then("The flooding proxy will disappear")
        eventually { ensureCurrentFpIs(tzId, null, null) }

        When("A new host appears")
        testOnOneHost(tzId, IPv4Addr.random, 100)
    }

    "The FloodingProxyManager" should "be resilient to ZK hiccups" in {
        fpManager.start()
        val tzId = makeTz()
        val tunIp = IPv4Addr.random
        val hId = testOnOneHost(tzId, tunIp)

        zkServer.stop()
        Thread.sleep(2000)
        zkServer.restart()

        // And things continue working as normal

        eventually { ensureCurrentFpIs(tzId, hId, tunIp) }
        toggleAlive(hId, isAlive = false)
        eventually { ensureCurrentFpIs(tzId, null, null) }
        toggleAlive(hId, isAlive = true)
        eventually { ensureCurrentFpIs(tzId, hId, tunIp) }
    }

    "The FloodingProxyManager" should "handle tunnel zone deletions" in {
        fpManager.start()
        val tzId = makeTz()
        testOnOneHost(tzId, IPv4Addr.random)
        backend.store.delete(classOf[TunnelZone], tzId)
        eventually { ensureCurrentFpIs(tzId, null, null) }
    }

    "The FloodingProxyManager" should "handle host deletions" in {
        fpManager.start()
        val tzId = makeTz()
        val hId = testOnOneHost(tzId, IPv4Addr.random)
        backend.store.delete(classOf[Host], hId)
        eventually { ensureCurrentFpIs(tzId, null, null) }
    }

    private def makeTz(): UUID = {
        val tz = createTunnelZone(UUID.randomUUID(), TunnelZone.Type.VTEP)
        val tzId = fromProto(tz.getId)
        backend.store.create(tz)
        tzId
    }

    private def testOnOneHost(tzId: UUID, tunIp: IPv4Addr,
                              weight: Int = 1): UUID = {
        val h1 = createHost().toBuilder.setFloodingProxyWeight(weight)
                                       .build()
        backend.store.create(h1)

        // Add it to the tunnel zone
        val tz = backend.store.get(classOf[TunnelZone], tzId).await()
        val newTz = tz.toBuilder.addHosts(HostToIp.newBuilder()
                                          .setHostId(h1.getId)
                                          .setIp(IPAddressUtil.toProto(tunIp))
                                          .build())
                                .addHostIds(h1.getId)
                                .build()

        backend.store.update(newTz)

        val h1Id = fromProto(h1.getId)
        toggleAlive(h1Id)
        eventually { ensureCurrentFpIs(tzId, h1Id, tunIp) }
        h1Id
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
            currentFp(tzId) shouldBe Option(FloodingProxy(hostId, tunIp))
        }
    }
}
