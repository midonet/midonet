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

package org.midonet.cluster.state

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import org.scalatest.{FlatSpec, Matchers, GivenWhenThen}

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.{StateResult, ZookeeperObjectMapper}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend.RoutesKey
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.{MidonetBackendTest, ParentDeletedException, CuratorTestFramework}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.util.reactivex._

class RoutingTableStorageTest extends FlatSpec with MidonetBackendTest
                                      with Matchers with GivenWhenThen
                                      with TopologyBuilder {

    private var storage: ZookeeperObjectMapper = _
    private val hostId = UUID.randomUUID
    private var ownerId: Long = _
    private val random = new Random
    private final val timeout = 5 seconds

    protected override def setup(): Unit = {
        storage = new ZookeeperObjectMapper(zkRoot, hostId.toString, curator,
                                            reactor, connection, connectionWatcher)
        ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        initAndBuildStorage(storage)
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        storage.registerClass(classOf[Port])
        storage.registerKey(classOf[Port], RoutesKey, Multiple)
        storage.build()
    }

    private def createPortRoute(portId: UUID = UUID.randomUUID) = {
        new Route(random.nextInt(), 24, random.nextInt(), 24, NextHop.PORT,
                  portId, random.nextInt(), random.nextInt(), "",
                  UUID.randomUUID, true)
    }

    "Store" should "add a port route to the routing table" in {
        val port = createRouterPort()
        storage.create(port)

        val route = createPortRoute(portId = port.getId)
        storage.addRoute(route).await(timeout) shouldBe StateResult(ownerId)

        storage.getPortRoutes(port.getId, hostId).await(timeout) shouldBe Set(route)
    }

    "Store" should "remove a port route from the routing table" in {
        val port = createRouterPort()
        storage.create(port)

        val route = createPortRoute(portId = port.getId)
        storage.addRoute(route).await(timeout) shouldBe StateResult(ownerId)
        storage.removeRoute(route).await(timeout) shouldBe StateResult(ownerId)

        storage.getPortRoutes(port.getId, hostId).await(timeout) shouldBe Set()
    }

    "Store observable" should "emit notifications on port updates" in {
        val port = createRouterPort()
        storage.create(port)

        val obs = new TestObserver[Set[Route]] with AwaitableObserver[Set[Route]]
        storage.portRoutesObservable(port.getId, Observable.just(hostId))
               .subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe Set()

        val route1 = createPortRoute(portId = port.getId)
        storage.addRoute(route1).await(timeout)

        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1) shouldBe Set(route1)

        val route2 = createPortRoute(portId = port.getId)
        storage.addRoute(route2).await(timeout)

        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2) shouldBe Set(route1, route2)

        storage.removeRoute(route1).await(timeout)

        obs.awaitOnNext(4, timeout) shouldBe true
        obs.getOnNextEvents.get(3) shouldBe Set(route2)

        storage.removeRoute(route2).await(timeout)

        obs.awaitOnNext(5, timeout) shouldBe true
        obs.getOnNextEvents.get(4) shouldBe Set()
    }

    "Store observable" should "emit error on port deletion" in {
        val port = createRouterPort()
        storage.create(port)

        val obs = new TestObserver[Set[Route]] with AwaitableObserver[Set[Route]]
        storage.portRoutesObservable(port.getId, Observable.just(hostId))
               .subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe Set()

        storage.delete(classOf[Port], port.getId)

        obs.awaitCompletion(timeout)
        obs.getOnCompletedEvents shouldBe empty
        obs.getOnErrorEvents should have size 1
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[ParentDeletedException]
    }
}
