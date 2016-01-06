/*
 * Copyright 2014 Midokura SARL
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

import java.util.{Random, UUID}

import scala.collection.immutable.{HashMap => IMap}
import scala.collection.mutable.{HashMap => MMap}
import scala.reflect.ClassTag

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.observers.TestObserver
import rx.subjects.PublishSubject
import rx.{Observable, Subscriber}

import org.midonet.midolman.l4lb.HealthMonitor.{ConfigAdded, ConfigDeleted, ConfigUpdated, HealthMonitorMessage}
import org.midonet.midolman.simulation.{LoadBalancer => SimLoadBalancer, PoolMember => SimPoolMember, Vip => SimVip}
import org.midonet.midolman.state.l4lb.{HealthMonitorType, LBStatus, VipSessionPersistence}
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.{HealthMonitor => SimHealthMonitor, PoolHealthMonitor, PoolHealthMonitorMap}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr


@RunWith(classOf[JUnitRunner])
class HealthMonitorConfigWatcherTest extends MidolmanSpec {

    val random = new Random()
    var watcher: TestableHealthMonitorConfigWatcher = null

    val uuidOne = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val uuidTwo = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val uuidThree = UUID.fromString("00000000-0000-0000-0000-000000000003")

    var phmObservable: PublishSubject[PoolHealthMonitorMap] = _

    class TestableHealthMonitorConfigWatcher
        extends HealthMonitorConfigWatcher("/doesnt/matter", "/dont/care") {

        override def call(child: Subscriber[_ >: HealthMonitorMessage]): Unit = {
            phmObservable.subscribe(observer)
            observable.subscribe(child)
        }

        override def subscribe[D <: Device](id: UUID)(implicit tag: ClassTag[D])
        : Unit = { }
    }

    protected override def beforeTest(): Unit = {
        watcher = new TestableHealthMonitorConfigWatcher()
        phmObservable = PublishSubject.create[PoolHealthMonitorMap]()
    }

    private def generateFakeData(poolId: UUID): PoolHealthMonitor = {
        val hm = new SimHealthMonitor(UUID.randomUUID(),
                                      adminStateUp = true,
                                      HealthMonitorType.TCP,
                                      LBStatus.ACTIVE,
                                      delay = 1,
                                      timeout = 1,
                                      maxRetries = 1)

        val vip = new SimVip(UUID.randomUUID(),
                             adminStateUp = true,
                             poolId,
                             IPv4Addr.random,
                             protocolPort = random.nextInt(65533) + 1,
                             sessionPersistence =
                                 VipSessionPersistence.SOURCE_IP)

        val lb = new SimLoadBalancer(UUID.randomUUID(),
                                     adminStateUp = true,
                                     routerId = UUID.randomUUID(),
                                     Array(vip))

        PoolHealthMonitor(hm, lb, Array(vip), Array[SimPoolMember]())
    }

    private def generateFakeMap(): MMap[UUID, PoolHealthMonitor] = {
        val map = new MMap[UUID, PoolHealthMonitor]()
        map.put(uuidOne, generateFakeData(uuidOne))
        map.put(uuidTwo, generateFakeData(uuidTwo))
        map.put(uuidThree, generateFakeData(uuidThree))
        map
    }

    private def observerAndSubscribe(): TestObserver[HealthMonitorMessage] = {
        val observer = new TestObserver[HealthMonitorMessage]()
        Observable.create(watcher).subscribe(observer)
        observer
    }

    feature("The config watcher only sends us updates if it is the leader") {
        scenario("config watcher is not the leader") {
            Given("A pool-health monitor mapping")
            val map = IMap(generateFakeMap().toSeq:_*)
            val observer = observerAndSubscribe()
            When("We send it to the config watcher")
            phmObservable onNext PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            observer.getOnCompletedEvents should have size 0
            And("We send more updates")
            phmObservable onNext PoolHealthMonitorMap(map)
            Then("We should expect nothing in return")
            observer.getOnCompletedEvents should have size 0
            phmObservable onNext PoolHealthMonitorMap(new IMap[UUID, PoolHealthMonitor]())
            observer.getOnCompletedEvents should have size 0
            phmObservable onNext PoolHealthMonitorMap(map)
            observer.getOnCompletedEvents should have size 0
            map(uuidOne).healthMonitor.delay = 2
            phmObservable onNext PoolHealthMonitorMap(map)
            observer.getOnCompletedEvents should have size 0
        }
        scenario("config watcher becomes the leader after several updates " +
                 "are sent") {
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            val observer = observerAndSubscribe()
            When("We send it to the config watcher")
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should expect nothing in return")
            observer.getOnNextEvents should have size 0
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            observer.getOnNextEvents should have size 0
            phmObservable onNext PoolHealthMonitorMap(new IMap[UUID, PoolHealthMonitor]())
            observer.getOnNextEvents should have size 0
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            observer.getOnNextEvents should have size 0
            map(uuidOne).healthMonitor.delay = 2
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            When("We become the leader node")
            watcher.becomeHaproxyNode
            val res = new MMap[UUID, PoolConfig]
            Then("We should receive the correct map")
            val conf1 = observer.getOnNextEvents.get(0).asInstanceOf[ConfigAdded]
            res put (conf1.poolId, conf1.config)
            val conf2 = observer.getOnNextEvents.get(1).asInstanceOf[ConfigAdded]
            res put (conf2.poolId, conf2.config)
            val conf3 = observer.getOnNextEvents.get(2).asInstanceOf[ConfigAdded]
            res put (conf3.poolId, conf3.config)
            res(uuidOne).healthMonitor.delay shouldEqual 2
            res(uuidTwo).healthMonitor.delay shouldEqual 1
            res(uuidThree).healthMonitor.delay shouldEqual 1
        }
    }

    feature("The config watcher updates based on changes") {
        scenario("pool config is deleted") {
            watcher.becomeHaproxyNode()
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            val observer = observerAndSubscribe()
            When("We send it to the config watcher")
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should receive the ConfigAdded for each mapping")
            observer.getOnNextEvents should have size 3
            for (i <- 0 to 2) observer.getOnNextEvents.get(i).getClass shouldBe
                                  classOf[ConfigAdded]
            map remove uuidTwo
            And("We remove one of the maps")
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should receive the deletion back")
            val conf = observer.getOnNextEvents.get(3).asInstanceOf[ConfigDeleted]
            conf.id shouldEqual uuidTwo
        }
        scenario("pool config is updated") {
            watcher.becomeHaproxyNode()
            Given("A pool-health monitor mapping")
            val map = generateFakeMap()
            val observer = observerAndSubscribe()
            When("We send it to the config watcher")
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should recieve the ConfigAdded for each mapping")
            observer.getOnNextEvents should have size 3
            for (i <- 0 to 2) observer.getOnNextEvents.get(i).getClass shouldBe
                                  classOf[ConfigAdded]
            And("We update one of the maps")
            map(uuidTwo).healthMonitor.delay = 10
            phmObservable onNext PoolHealthMonitorMap(IMap(map.toSeq:_*))
            Then("We should receive the update notification back")
            val conf = observer.getOnNextEvents.get(3).asInstanceOf[ConfigUpdated]
            conf.config.healthMonitor.delay shouldEqual 10
        }
    }
}
