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


import scala.collection.JavaConversions._
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.storage.{TestAwaitableObserver, Storage}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.models.{Topology => Proto}
import org.midonet.midolman.topology.devices.PoolHealthMonitorMap
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class PoolHealthMonitorMapperTest extends MidolmanSpec
                                          with TopologyBuilder
                                          with TopologyMatchers {

    type SimObserver = TestAwaitableObserver[PoolHealthMonitorMap]

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).ownershipStore
    }

    private def assertThread(): Unit = {
        assert(vt.threadId == Thread.currentThread.getId)
    }

    feature("The mapper emits pool health monitor maps") {
        scenario("The mapper emits an empty map") {
            Given("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see an empty map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.head.mappings.isEmpty shouldBe true
        }

        scenario("The mapper emits existing pool") {
            Given("A health monitor")
            val hm = createTopologyObject(classOf[Proto.HealthMonitor])

            And("A load balancer")
            val lb = createTopologyObject(classOf[Proto.LoadBalancer])

            And("A pool")
            var pool = createTopologyObject(classOf[Proto.Pool], Map(
                "health_monitor_id" -> hm.getId,
                "load_balancer_id" -> lb.getId
            ))

            And("A pool member")
            val poolMember = createTopologyObject(classOf[Proto.PoolMember], Map(
                "pool_id" -> pool.getId
            ))
            pool = pool.toBuilder.addAllPoolMemberIds(List(poolMember.getId)).build

            And("A vip")
            val vip = createTopologyObject(classOf[Proto.VIP], Map(
                "pool_id" -> pool.getId,
                "load_balancer_id" -> lb.getId
            ))


            /*********************************************/

            And("A pool health monitor mapper")
            val mapper = new PoolHealthMonitorMapper(vt)

            And("An observer to the pool health monitor mapper")
            val obs = new SimObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive a pool")
        }
    }
}
