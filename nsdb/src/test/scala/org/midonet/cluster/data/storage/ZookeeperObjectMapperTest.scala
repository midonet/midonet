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
package org.midonet.cluster.data.storage

import java.util.{ConcurrentModificationException, UUID}

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.GivenWhenThen
import org.scalatest.junit.JUnitRunner
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.util.{ClassAwaitableObserver, CuratorTestFramework, PathCacheDisconnectedException}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTest extends StorageTest with CuratorTestFramework
                                with GivenWhenThen {

    import StorageTest._

    private val timeout = 5 seconds
    private val hostId = UUID.randomUUID.toString

    protected override def setup(): Unit = {
        storage = createStorage
        assert = () => {}
        initAndBuildStorage(storage)
    }

    protected override def createStorage = {
        new ZookeeperObjectMapper(zkRoot, hostId, curator)
    }

    feature("Test subscribe") {
        scenario("Test object observable recovers after close") {
            Given("A bridge")
            val bridge = createPojoBridge()
            storage.create(bridge)

            And("An observer")
            val observer = new TestObserver[PojoBridge]
                               with AwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs = storage.observable(classOf[PojoBridge], bridge.id)

            When("The observer subscribes to the observable")
            val sub = obs.subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents should have size 1

            When("The observer unsubscribes")
            sub.unsubscribe()

            Then("The storage does not return the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true

            When("The observer resubscribes")
            storage.observable(classOf[PojoBridge], bridge.id)
                   .subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents should have size 2

            And("The storage returns a different observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true
        }

        scenario("Test object observable is reused by concurrent subscribers") {
            Given("A bridge")
            val bridge = createPojoBridge()
            storage.create(bridge)

            And("An observer")
            val observer = new TestObserver[PojoBridge]
                               with AwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs = storage.observable(classOf[PojoBridge], bridge.id)

            When("The observer subscribes to the observable")
            val sub1 = obs.subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents should have size 1

            When("The observer subscribes a second time to the observable")
            val sub2 = obs.subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents should have size 2

            When("The first subscription unsubscribes")
            sub1.unsubscribe()

            Then("The storage does not return the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true

            When("The observer resubscribes")
            storage.observable(classOf[PojoBridge], bridge.id)
                .subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(3, timeout)
            observer.getOnNextEvents should have size 3

            And("The storage does not return the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true
        }

        scenario("Test subscribe all with GC") {
            val obs = new ClassAwaitableObserver[PojoBridge](0)
            val sub = storage.observable(classOf[PojoBridge]).subscribe(obs)

            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.subscriptionCount(classOf[PojoBridge]) shouldBe Option(1)
            sub.unsubscribe()
            zoom.subscriptionCount(classOf[PojoBridge]) shouldBe None

            obs.getOnCompletedEvents should have size 0
            obs.getOnErrorEvents should have size 1
            assert(obs.getOnErrorEvents.get(0)
                       .isInstanceOf[PathCacheDisconnectedException])
        }
    }


    feature("Test transactions") {
        scenario("Get fails on non-existing object") {
            val tx = storage.transaction()
            intercept[NotFoundException] {
                tx.get(classOf[PojoBridge], UUID.randomUUID())
            }
        }

        scenario("Get succeeds on existing object") {
            val bridge = createPojoBridge()
            storage.create(bridge)
            val tx = storage.transaction()
            tx.get(classOf[PojoBridge], bridge.id) shouldBe bridge
        }

        scenario("Get returns a transaction-local object") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)
            val tx = storage.transaction()
            tx.get(classOf[PojoBridge], bridge1.id) shouldBe bridge1

            val bridge2 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge2)
            tx.get(classOf[PojoBridge], bridge1.id) shouldBe bridge1
        }

        scenario("Get fails if an object is modified during the transaction") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)
            val tx = storage.transaction()

            val bridge2 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge2)

            intercept[ConcurrentModificationException] {
                tx.get(classOf[PojoBridge], bridge1.id) shouldBe bridge1
            }
        }

        scenario("Get all succeeds for no objects") {
            val tx = storage.transaction()
            tx.getAll(classOf[PojoBridge]) shouldBe empty
        }

        scenario("Get all succeeds for existing objects") {
            val bridge1 = createPojoBridge()
            val bridge2 = createPojoBridge()
            val bridge3 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2),
                              CreateOp(bridge3)))

            val tx = storage.transaction()
            tx.getAll(classOf[PojoBridge]) should contain theSameElementsAs Seq(
                bridge1, bridge2, bridge3)
        }

        scenario("Get all fails if new objects are created after the transaction") {
            val bridge1 = createPojoBridge()
            val bridge2 = createPojoBridge()
            val bridge3 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2),
                              CreateOp(bridge3)))

            val tx = storage.transaction()

            val bridge4 = createPojoBridge()
            storage.create(bridge4)

            intercept[ConcurrentModificationException] {
                tx.getAll(classOf[PojoBridge])
            }
        }

        scenario("Get all succeeds if objects are deleted after the transaction") {
            val bridge1 = createPojoBridge()
            val bridge2 = createPojoBridge()
            val bridge3 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2),
                              CreateOp(bridge3)))

            val tx = storage.transaction()

            storage.delete(classOf[PojoBridge], bridge1.id)
            tx.getAll(classOf[PojoBridge]) should contain theSameElementsAs Seq(
                bridge2, bridge3)
        }

        scenario("Get all fails if objects are modified during the transaction") {
            val bridge1 = createPojoBridge(name = "name-1")
            val bridge2 = createPojoBridge()
            val bridge3 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2),
                              CreateOp(bridge3)))

            val tx = storage.transaction()

            val bridge4 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge4)

            intercept[ConcurrentModificationException] {
                tx.getAll(classOf[PojoBridge])
            }
        }

        scenario("Get all by ids fails on non existing objects") {
            val tx = storage.transaction()
            intercept[NotFoundException] {
                tx.getAll(classOf[PojoBridge],
                          Seq(UUID.randomUUID(), UUID.randomUUID()))
            }
        }

        scenario("Get all by ids fails if some objects do not exist") {
            val bridge = createPojoBridge()
            storage.create(bridge)

            val tx = storage.transaction()
            intercept[NotFoundException] {
                tx.getAll(classOf[PojoBridge],
                          Seq(bridge.id, UUID.randomUUID()))
            }
        }

        scenario("Get all by ids succeeds if all objects exist") {
            val bridge1 = createPojoBridge()
            val bridge2 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2)))

            val tx = storage.transaction()
            tx.getAll(classOf[PojoBridge], Seq(bridge1.id, bridge2.id)) should contain allOf(
                bridge1, bridge2)
        }

        scenario("Get all by ids returns transaction-local objects") {
            val bridge1 = createPojoBridge(name = "name-1")
            val bridge2 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2)))

            val tx = storage.transaction()
            tx.get(classOf[PojoBridge], bridge1.id) shouldBe bridge1

            val bridge3 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge3)
            tx.getAll(classOf[PojoBridge], Seq(bridge1.id, bridge2.id)) should contain allOf(
                bridge1, bridge2)
        }

        scenario("Get all by ids fails if an object is modified during the transaction") {
            val bridge1 = createPojoBridge(name = "name-1")
            val bridge2 = createPojoBridge()
            storage.multi(Seq(CreateOp(bridge1), CreateOp(bridge2)))

            val tx = storage.transaction()

            val bridge3 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge3)

            intercept[ConcurrentModificationException] {
                tx.getAll(classOf[PojoBridge], Seq(bridge1.id, bridge2.id))
            }
        }

        scenario("Create succeeds if object does not exist") {
            val bridge = createPojoBridge()
            val tx = storage.transaction()

            tx.create(bridge)
            await(storage.exists(classOf[PojoBridge], bridge.id)) shouldBe false
            tx.get(classOf[PojoBridge], bridge.id) shouldBe bridge

            tx.commit()
            await(storage.get(classOf[PojoBridge], bridge.id)) shouldBe bridge
        }

        scenario("Create fails if the object exists") {
            val bridge = createPojoBridge()
            val tx = storage.transaction()

            tx.create(bridge)
            await(storage.exists(classOf[PojoBridge], bridge.id)) shouldBe false
            tx.get(classOf[PojoBridge], bridge.id) shouldBe bridge

            storage.create(bridge)
            intercept[ObjectExistsException] {
                tx.commit()
            }
        }

        scenario("Update fails on non-existing object") {
            val bridge = createPojoBridge()
            val tx = storage.transaction()

            intercept[NotFoundException] {
                tx.update(bridge)
            }
        }

        scenario("Update succeeds on existing object") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val bridge2 = createPojoBridge(id = bridge1.id, name = "name-2")
            val tx = storage.transaction()
            tx.update(bridge2)
            tx.commit()

            await(storage.get(classOf[PojoBridge], bridge1.id)) shouldBe bridge2
        }

        scenario("Update succeeds on transaction-local object") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val tx = storage.transaction()
            val bridge2 = tx.get(classOf[PojoBridge], bridge1.id)
            val bridge3 = createPojoBridge(id = bridge2.id, name = "name-2")
            tx.update(bridge3)
            tx.commit()

            await(storage.get(classOf[PojoBridge], bridge1.id)) shouldBe bridge3
        }

        scenario("Update fails if the existing object is modified before get") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val tx = storage.transaction()
            val bridge2 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge2)

            intercept[ConcurrentModificationException] {
                tx.get(classOf[PojoBridge], bridge1.id)
            }
        }

        scenario("Update fails if the existing object is modified before commit") {
            val bridge1 = createPojoBridge()
            storage.create(bridge1)

            val tx = storage.transaction()
            val bridge2 = tx.get(classOf[PojoBridge], bridge1.id)

            val bridge3 = createPojoBridge(id = bridge2.id, name = "name-1")
            tx.update(bridge3)

            val bridge4 = createPojoBridge(id = bridge2.id, name = "name-2")
            storage.update(bridge4)

            intercept[ConcurrentModificationException] {
                tx.commit()
            }
        }

        scenario("Update fails if the existing object is modified indirectly before commit") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val tx = storage.transaction()
            val bridge2 = tx.get(classOf[PojoBridge], bridge1.id)

            val bridge3 = createPojoBridge(id = bridge2.id, name = "name-2")
            tx.update(bridge3)

            val port = createPojoPort(bridgeId = bridge1.id)
            storage.create(port)

            intercept[ConcurrentModificationException] {
                tx.commit()
            }
        }

        scenario("Update fails if the existing object is deleted before commit") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val tx = storage.transaction()
            val bridge2 = tx.get(classOf[PojoBridge], bridge1.id)

            val bridge3 = createPojoBridge(id = bridge2.id, name = "name-2")
            tx.update(bridge3)

            storage.delete(classOf[PojoBridge], bridge1.id)

            intercept[ConcurrentModificationException] {
                tx.commit()
            }
        }

        scenario("Update from two transactions only one succeeds") {
            val bridge1 = createPojoBridge(name = "name-0")
            storage.create(bridge1)

            val tx1 = storage.transaction()
            val bridge1_1 = tx1.get(classOf[PojoBridge], bridge1.id)

            val tx2 = storage.transaction()
            val bridge2_1 = tx2.get(classOf[PojoBridge], bridge1.id)

            val bridge1_2 = createPojoBridge(id = bridge1_1.id, name = "name-1")
            tx1.update(bridge1_2)

            val bridge2_2 = createPojoBridge(id = bridge2_1.id, name = "name-2")
            tx2.update(bridge2_2)

            tx1.commit()

            intercept[ConcurrentModificationException] {
                tx2.commit()
            }
        }

        scenario("Delete fails on non-existing object") {
            val tx = storage.transaction()
            intercept[NotFoundException] {
                tx.delete(classOf[PojoBridge], UUID.randomUUID())
            }
        }

        scenario("Delete succeeds on existing object") {
            val bridge = createPojoBridge()
            storage.create(bridge)

            val tx = storage.transaction()
            tx.delete(classOf[PojoBridge], bridge.id)
            tx.commit()

            await(storage.exists(classOf[PojoBridge], bridge.id)) shouldBe false
        }

        scenario("Delete fails if the object is modified before delete") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val tx = storage.transaction()

            val bridge2 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge2)

            intercept[ConcurrentModificationException] {
                tx.delete(classOf[PojoBridge], bridge1.id)
            }
        }

        scenario("Delete fails of the object is modified before commit") {
            val bridge1 = createPojoBridge(name = "name-1")
            storage.create(bridge1)

            val tx = storage.transaction()
            tx.delete(classOf[PojoBridge], bridge1.id)

            val bridge2 = createPojoBridge(id = bridge1.id, name = "name-2")
            storage.update(bridge2)

            intercept[ConcurrentModificationException] {
                tx.commit()
            }
        }

        scenario("Delete fails if the object is deleted before delete") {
            val bridge = createPojoBridge()
            storage.create(bridge)

            val tx = storage.transaction()

            storage.delete(classOf[PojoBridge], bridge.id)

            intercept[NotFoundException] {
                tx.delete(classOf[PojoBridge], bridge.id)
            }
        }

        scenario("Delete fails if the object is deleted before commit") {
            val bridge = createPojoBridge()
            storage.create(bridge)

            val tx = storage.transaction()
            tx.delete(classOf[PojoBridge], bridge.id)

            storage.delete(classOf[PojoBridge], bridge.id)

            intercept[ConcurrentModificationException] {
                tx.commit()
            }
        }
    }

    feature("Test Zookeeper") {
        scenario("Test get path") {
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.classPath(classOf[PojoBridge]) shouldBe s"$zkRoot/${zoom.version}/models/PojoBridge"
        }
    }
}
