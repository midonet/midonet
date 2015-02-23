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

import scala.concurrent.duration._

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Router => TopologyRouter}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.FlowController
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{Router => SimulationRouter}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class RouterMapperTest extends MidolmanSpec with TopologyBuilder
                               with TopologyMatchers {

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var config: MidolmanConfig = _
    private var threadId: Long = _

    private final val timeout = 5 seconds

    registerActors(FlowController -> (() => new FlowController
                                                with MessageAccumulator))

    def fc = FlowController.as[FlowController with MessageAccumulator]

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        config = injector.getInstance(classOf[MidolmanConfig])
        threadId = Thread.currentThread.getId
    }

    protected override def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        super.fillConfig(config)
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config
    }

    private def createObserver(count: Int = 1)
    : AwaitableObserver[SimulationRouter] = {
        Given("An observer for the router mapper")
        // It is possible to receive the initial notification on the current
        // thread, when the device was notified in the mapper's behavior subject
        // previous to the subscription.
        new AwaitableObserver[SimulationRouter](
            count, assert(vt.vtThreadId == Thread.currentThread.getId ||
                          threadId == Thread.currentThread.getId))
    }

    private def testRouterCreated(routerId: UUID,
                                  obs: AwaitableObserver[SimulationRouter],
                                  count: Int, test: Int)
    : TopologyRouter = {
        Given("A router mapper")
        val mapper = new RouterMapper(routerId, vt)

        And("A router")
        val router = createRouter(id = routerId)

        When("The bridge is created")
        store.create(router)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)

        Then("The observer should receive the router device")
        obs.await(5 seconds, count) shouldBe true
        val device = obs.getOnNextEvents.get(test)
        device shouldBeDeviceOf router

        router
    }

    private def testRouterUpdated(router: TopologyRouter,
                                  obs: AwaitableObserver[SimulationRouter],
                                  count: Int, test: Int)
    : SimulationRouter = {
        When("The router is updated")
        store.update(router)

        Then("The observer should receive the update")
        obs.await(timeout, count) shouldBe true
        val device = obs.getOnNextEvents.get(test)
        device shouldBeDeviceOf router

        device
    }

    private def testRouterDeleted(routerId: UUID,
                                  obs: AwaitableObserver[SimulationRouter],
                                  count: Int)
    : Unit = {
        When("The router is deleted")
        store.delete(classOf[TopologyRouter], routerId)

        Then("The observer should receive a completed notification")
        obs.await(timeout, count) shouldBe true
        obs.getOnCompletedEvents should not be empty
    }

    feature("Router mapper emits notifications for router update") {
        scenario("The mapper emits error for non-existing router") {
            Given("A router identifier")
            val routerId = UUID.randomUUID

            And("A router mapper")
            val mapper = new RouterMapper(routerId, vt)

            And("An observer to the router mapper")
            val obs = new AwaitableObserver[SimulationRouter]

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.await(timeout) shouldBe true
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyRouter]
            e.id shouldBe routerId
        }

        scenario("The mapper emits existing bridge") {
            val routerId = UUID.randomUUID
            val obs = createObserver(1)
            testRouterCreated(routerId, obs, count = 0, test = 0)
        }

        scenario("The mapper emits new device on router update") {
            val routerId = UUID.randomUUID
            val obs = createObserver(1)
            testRouterCreated(routerId, obs, count = 1, test = 0)
            val routerUpdate = createRouter(id = routerId, adminStateUp = true)
            testRouterUpdated(routerUpdate, obs, count = 0, test = 1)
        }

        scenario("The mapper completes on router delete") {
            val routerId = UUID.randomUUID
            val obs = createObserver(1)
            testRouterCreated(routerId, obs, count = 1, test = 0)
            testRouterDeleted(routerId, obs, count = 0)
        }
    }
}
