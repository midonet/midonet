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

import scala.collection.JavaConversions._
import scala.concurrent.duration.DurationLong

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import mockit.Mocked
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Router
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.state.zkManagers.{RouteZkManager, RouterZkManager}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class RouterMapperTest extends TestKit(ActorSystem("RouterMapperTest"))
                       with MidolmanSpec
                       with TopologyBuilder {

    private class TestableRouterMapper(id: UUID, vt: VirtualTopology,
                                       routerMgr: RouterZkManager,
                                       routeMgr: RouteZkManager)
        extends RouterMapper(id, vt, routerMgr, routeMgr)(actorSystem)

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var routerMapper: TestableRouterMapper = _

    private val routerId = UUID.randomUUID()

    @Mocked private var routerMgr: RouterZkManager = _
    @Mocked private var routeMgr: RouteZkManager = _

    registerActors(RouterMapper -> (() => new TestableRouterMapper(routerId, vt, routerMgr,
                                                                   routeMgr)))
//    registerActors(VirtualTopologyActor -> (() => new TestableVTA))
//    registerActors(FlowController -> (() => new FlowController))

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
        routerMapper = RouterMapper.as[TestableRouterMapper]
    }

    private def createZoomRouter: Router = {
        val router = createRouterBuilder(routerId,
                                         "tenant_id",
                                         "test_router",
                                         true /*adminStateUp*/,
                                         UUID.randomUUID(),
                                         UUID.randomUUID())
            .build()

        store.create(router)
        router
    }

    private def assertEquals(router: Router, simRouter: SimRouter) = {
        router.getId shouldBe simRouter.id
        router.getAdminStateUp shouldBe simRouter.cfg.adminStateUp
        router.getInboundFilterId shouldBe simRouter.cfg.inboundFilter
        router.getOutboundFilterId shouldBe simRouter.cfg.outboundFilter
    }

    feature("The router mapper successfully delivers routers") {
        scenario("Asking for a router with VirtualTopology.get") {
            Given("A topology with one router")
            val protoRouter = createZoomRouter

            When("When we subscribe to the router")
            val obs = new AwaitableObserver[SimRouter](1)
            routerMapper.observable.subscribe(obs)

            Then("We receive the router")
            obs.await(1 second) shouldBe true
            val simRouter = obs.getOnNextEvents.last
            assertEquals(protoRouter, simRouter)
        }
    }
}
