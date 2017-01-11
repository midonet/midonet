/*
 * Copyright 2016 Midokura SARL
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

import com.google.common.collect.ArrayListMultimap

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZookeeperObjectMapper.MessageClassInfo
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.TestModels.FakeDevice

@RunWith(classOf[JUnitRunner])
class TransactionManagerTest extends FeatureSpec with Matchers
                                     with GivenWhenThen {

    private val classes: ClassesMap =
        Map(classOf[FakeDevice] -> new MessageClassInfo(classOf[FakeDevice]))
    private val bindings =
        ArrayListMultimap.create[Class[_], FieldBinding]()

    private val defaultId = UUID.newBuilder().setLsb(0L).setMsb(0L).build()
    private val notFoundId = UUID.newBuilder().setLsb(1L).setMsb(1L).build()
    private val defaultDevice = FakeDevice.newBuilder().setId(defaultId).build()
    private val notFoundDevice = FakeDevice.newBuilder().setId(notFoundId).build()
    private val defaultSnapshot = ObjSnapshot(defaultDevice, 0)

    private class TestableTransactionManager
        extends TransactionManager(classes, bindings) {

        var getSnapshotCount = 0
        var getIdsCount = 0

        protected override def isRegistered(clazz: Class[_]): Boolean = {
            classes.contains(clazz)
        }

        protected override def getSnapshot(clazz: Class[_], id: ObjId)
        : Observable[ObjSnapshot] = {
            getSnapshotCount += 1
            if (id == defaultId) Observable.just(defaultSnapshot)
            else Observable.error(new NotFoundException(clazz, id))
        }

        protected override def getIds(clazz: Class[_]): Observable[Seq[ObjId]] = {
            getIdsCount += 1
            Observable.just(Seq(defaultId))
        }

        protected override def nodeExists(path: String): Boolean = {
            true
        }

        protected override def childrenOf(path: String): Seq[String] = {
            Seq()
        }

        override def commit(): Unit = { }

        override def close(): Unit = { }

        def getOps = ops
    }

    feature("Transaction manager handles read operations") {
        scenario("Transaction manager handles get()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting an object")
            val obj = manager.get(classOf[FakeDevice], defaultId)

            Then("The manager returns the object")
            obj shouldBe defaultDevice

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager caches snapshots on get()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting an object twice")
            manager.get(classOf[FakeDevice], defaultId)
            manager.get(classOf[FakeDevice], defaultId)

            Then("The manager should have requested the snapshot once")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager throws not found on get()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            Then("Requesting a non-existing object fails")
            intercept[NotFoundException] {
                manager.get(classOf[FakeDevice], notFoundId)
            }

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager handles getAll()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting all objects")
            val list = manager.getAll(classOf[FakeDevice])

            Then("The manager returns the object")
            list should contain only defaultDevice

            And("The manager should have requested the identifiers")
            manager.getIdsCount shouldBe 1

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager handles getAll() with identifiers") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting all objects")
            val list = manager.getAll(classOf[FakeDevice], Seq(defaultId))

            Then("The manager returns the object")
            list should contain only defaultDevice

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager throws not found on getAll()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            Then("Requesting all objects fails")
            intercept[NotFoundException] {
                manager.getAll(classOf[FakeDevice], Seq(notFoundId))
            }

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager returns true for exists()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting whether an object exists")
            val result = manager.exists(classOf[FakeDevice], defaultId)

            Then("The manager returns true")
            result shouldBe true

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager caches result on exists()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting whether an object exists twice")
            manager.exists(classOf[FakeDevice], defaultId)
            manager.exists(classOf[FakeDevice], defaultId)

            Then("The manager should have requested the snapshot once")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("Transaction manager returns false for exists()") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting whether an object exists")
            val result = manager.exists(classOf[FakeDevice], notFoundId)

            Then("The manager returns false")
            result shouldBe false

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }
    }

    feature("Transaction manager handles create operations") {
        scenario("The object does not exist") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Creating an object")
            manager.create(defaultDevice)

            Then("The manager adds the create operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain (opKey -> TxCreate(defaultDevice))
        }

        scenario("The object exists") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting the object")
            manager.get(classOf[FakeDevice], defaultId)

            Then("Creating the same object should fail")
            intercept[ObjectExistsException] {
                manager.create(defaultDevice)
            }

            And("The manager does not add an operation")
            manager.getOps shouldBe empty
        }

        scenario("The object exists but is deleted") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting the object")
            manager.get(classOf[FakeDevice], defaultId)

            And("Deleting the object")
            manager.delete(classOf[FakeDevice], defaultId)

            And("Creating the same object")
            manager.create(defaultDevice)

            Then("The manager adds an update operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain (opKey -> TxUpdate(defaultDevice, 0))
        }

        scenario("The object is created twice") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            Then("Creating the same object twice should fail")
            manager.create(defaultDevice)
            intercept[ObjectExistsException] {
                manager.create(defaultDevice)
            }

            And("The manager adds a one create operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain (opKey -> TxCreate(defaultDevice))
        }

        scenario("The object is created if it does not exist") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Requesting whether the object exists")
            val exists = manager.exists(classOf[FakeDevice], notFoundId)

            Then("The object should not exist")
            exists shouldBe false

            And("Creating the same object")
            manager.create(notFoundDevice)

            Then("The manager adds a create operation")
            val opKey = Key(classOf[FakeDevice], getIdString(notFoundId))
            manager.getOps should contain (opKey -> TxCreate(notFoundDevice))
        }
    }

    feature("Transaction manager handles update operations") {
        scenario("Object does not exist") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            Then("Updating an object that does not exist fails")
            intercept[NotFoundException] {
                manager.update(notFoundDevice)
            }

            And("The manager does not add an operation")
            manager.getOps shouldBe empty
        }

        scenario("The object exists") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Updating an object")
            manager.update(defaultDevice)

            And("The manager adds an update operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain (opKey -> TxUpdate(defaultDevice, 0))

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1
        }

        scenario("With validator that returns new object") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            And("A validator")
            var validatorCount = 0
            val obj = FakeDevice.newBuilder().setId(defaultId)
                                             .setName("name").build()
            val validator = new UpdateValidator[Obj] {
                override def validate(oldObj: Obj, newObj: Obj): Obj = {
                    validatorCount += 1
                    obj
                }
            }

            When("Updating the object")
            manager.update(defaultDevice, validator)

            And("The manager adds an update operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain (opKey -> TxUpdate(obj, 0))

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1

            And("The manager should have called the validator")
            validatorCount shouldBe 1
        }

        scenario("With validator that returns null") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            And("A validator")
            var validatorCount = 0
            val validator = new UpdateValidator[Obj] {
                override def validate(oldObj: Obj, newObj: Obj): Obj = {
                    validatorCount += 1
                    null
                }
            }

            When("Updating the object")
            manager.update(defaultDevice, validator)

            And("The manager adds an update operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain (opKey -> TxUpdate(defaultDevice, 0))

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1

            And("The manager should have called the validator")
            validatorCount shouldBe 1
        }

        scenario("With validator that modifies the object identifier") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            And("A validator")
            var validatorCount = 0
            val validator = new UpdateValidator[Obj] {
                override def validate(oldObj: Obj, newObj: Obj): Obj = {
                    validatorCount += 1
                    notFoundDevice
                }
            }

            Then("Updating the object should fail")
            intercept[IllegalArgumentException] {
                manager.update(defaultDevice, validator)
            }

            And("The manager does not add an operation")
            manager.getOps shouldBe empty

            And("The manager should have requested the snapshot")
            manager.getSnapshotCount shouldBe 1

            And("The manager should have called the validator")
            validatorCount shouldBe 1
        }

        scenario("The object is deleted") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("The object is deleted")
            manager.delete(classOf[FakeDevice], defaultId)

            Then("Updating an object that does not exist fails")
            intercept[NotFoundException] {
                manager.update(notFoundDevice)
            }
        }

        scenario("The object is created in the same transaction") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("The object is created")
            manager.create(defaultDevice)

            And("The same object is updated")
            val newDevice = defaultDevice.toBuilder.setName("name").build()
            manager.update(newDevice)

            Then("The manager adds only a create operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain only (opKey -> TxCreate(newDevice))
        }

        scenario("The object is updated twice in the same transaction") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("The object is updated twice")
            val newDevice = defaultDevice.toBuilder.setName("name").build()
            manager.update(defaultDevice)
            manager.update(newDevice)

            Then("The manager adds only an update operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain only (opKey -> TxUpdate(newDevice, 0))

        }
    }

    feature("Transaction manager handles delete operations") {
        scenario("The object exists") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("The object is deleted")
            manager.delete(classOf[FakeDevice], defaultId)

            Then("The manager adds a delete operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain only (opKey -> TxDelete(0))
        }

        scenario("The object does not exist") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            Then("Deleting the object should fail")
            intercept[NotFoundException] {
                manager.delete(classOf[FakeDevice], notFoundId)
            }

            Then("The manager does not add an operation")
            manager.getOps shouldBe empty
        }

        scenario("The object is deleted twice") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            Then("Deleting the object twice should fail")
            manager.delete(classOf[FakeDevice], defaultId)
            intercept[NotFoundException] {
                manager.delete(classOf[FakeDevice], defaultId)
            }

            Then("The manager adds a delete operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain only (opKey -> TxDelete(0))
        }

        scenario("Deleting after creating should be no-op") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Creating an object")
            manager.create(defaultDevice)

            And("Deleting the same object")
            manager.delete(classOf[FakeDevice], defaultId)

            Then("The manager does not add any operation")
            manager.getOps shouldBe empty
        }

        scenario("Deleting after updating should remove the update") {
            Given("A transaction manager")
            val manager = new TestableTransactionManager

            When("Updating an object")
            manager.update(defaultDevice)

            And("Deleting the same object")
            manager.delete(classOf[FakeDevice], defaultId)

            Then("The manager adds only a delete operation")
            val opKey = Key(classOf[FakeDevice], getIdString(defaultId))
            manager.getOps should contain only (opKey -> TxDelete(0))
        }
    }

}
