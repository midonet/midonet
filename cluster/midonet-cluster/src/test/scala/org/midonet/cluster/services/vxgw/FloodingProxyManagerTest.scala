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
import org.scalatest._

import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.services.MidonetBackend.{AliveKey, TunnelZoneKey}
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.test.util.ZookeeperTestSuite
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil.{fromProto, toProto}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.util.MidonetEventually

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

    private def currentFp(tzId: UUID): Option[UUID] = {
        backend.stateStore
               .getKey(classOf[TunnelZone], tzId, TunnelZoneKey)
               .toBlocking.single() match {
                   case SingleValueKey(_, v, _) => v.map(UUID.fromString)
                   case _ => None
               }
    }

    "The happy case" should "yield happy results" in {
        fpManager.start()

        When("A tz of type VTEP is created")
        val tzId = makeTz()
        val h1Id = testOnOneHost(tzId, 10)

        When("The host goes down")
        toggleAlive(h1Id, isAlive = false)

        Then("The flooding proxy will disappear")
        eventually { ensureCurrentFpIs(tzId, null) }

        When("A new host appears")
        testOnOneHost(tzId, 100)
    }

    "The FloodingProxyManager" should "be resilient to ZK hiccups" in {
        fpManager.start()
        val tzId = makeTz()
        val hId = testOnOneHost(tzId)

        zkServer.stop()
        Thread.sleep(2000)
        zkServer.restart()

        // And things continue working as normal

        eventually { ensureCurrentFpIs(tzId, hId) }
        toggleAlive(hId, isAlive = false)
        eventually { ensureCurrentFpIs(tzId, null) }
        toggleAlive(hId, isAlive = true)
        eventually { ensureCurrentFpIs(tzId, hId) }
    }

    "The FloodingProxyManager" should "handle tunnel zone deletions" in {
        fpManager.start()
        val tzId = makeTz()
        testOnOneHost(tzId)
        backend.store.delete(classOf[TunnelZone], tzId)
        eventually { ensureCurrentFpIs(tzId, null) }
    }

    "The FloodingProxyManager" should "handle host deletions" in {
        fpManager.start()
        val tzId = makeTz()
        val hId = testOnOneHost(tzId)
        backend.store.delete(classOf[Host], hId)
        eventually { ensureCurrentFpIs(tzId, null) }
    }

    private def makeTz(): UUID = {
        val tz = createTunnelZone(UUID.randomUUID(), TunnelZone.Type.VTEP)
        val tzId = fromProto(tz.getId)
        backend.store.create(tz)
        tzId
    }

    private def testOnOneHost(tzId: UUID, weight: Int = 1): UUID = {
        val h1 = createHost().toBuilder.setFloodingProxyWeight(weight)
                                       .addTunnelZoneIds(toProto(tzId)).build()
        val h1Id = fromProto(h1.getId)
        backend.store.create(h1)
        toggleAlive(h1Id)
        eventually { ensureCurrentFpIs(tzId, h1Id) }
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

    private def ensureCurrentFpIs(tzId: UUID, hostId: UUID): Unit = {
        currentFp(tzId) shouldBe Option(hostId)
    }
}
