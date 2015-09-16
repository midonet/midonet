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

import scala.reflect.{ClassTag, classTag}

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{UpdateOp, CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.cluster.topology.TopologyBuilder._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.Pool
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.concurrent.ReactiveActor.{OnError, OnCompleted}

@RunWith(classOf[JUnitRunner])
class TopologyActorTest extends MidolmanSpec with TopologyBuilder
                        with TopologyMatchers {

    private var observer: TestObserver[Device] = _
    private var actor: ActorRef = _
    private var store: Storage = _

    private case class Subscribe(id: UUID, tag: ClassTag[_ <: Device])
    private case class Unsubscribe(id: UUID, tag: ClassTag[_ <: Device])

    private class TestableTopologyActor(obs: TestObserver[Device])
        extends TopologyActor {
        override def receive = {
            case Subscribe(id, tag) => subscribe(id)(tag)
            case Unsubscribe(id, tag) => unsubscribe(id)(tag)
            case d: Device => obs onNext d
            case OnCompleted => obs.onCompleted()
            case OnError(e) => obs onError e
        }
    }

    override def beforeTest() {
        observer = new TestObserver[Device]
        actor = TestActorRef(Props(new TestableTopologyActor(observer)))
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    feature("The actor can subscribe") {
        scenario("Subscribe to non existing device") {
            Given("A random pool identifier")
            val poolId = UUID.randomUUID()

            When("Subscribing to the pool via the actor")
            actor ! Subscribe(poolId, classTag[Pool])

            Then("The actor should receive an error")
            observer.getOnErrorEvents.get(0)
                    .getClass shouldBe classOf[NotFoundException]
            val e = observer.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[Topology.Pool]
            e.id shouldBe poolId
        }

        scenario("Subscribe to existing device") {
            Given("A pool")
            val pool = createPool()
            store create pool

            When("Subscribing to the pool via the actor")
            actor ! Subscribe(pool.getId, classTag[Pool])

            Then("The actor should receive the pool")
            observer.getOnNextEvents.get(0)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool
        }

        scenario("Actor receives device updates") {
            Given("A pool")
            val pool1 = createPool()
            store create pool1

            When("Subscribing to the pool via the actor")
            actor ! Subscribe(pool1.getId, classTag[Pool])

            And("Updating the pool")
            val pool2 = pool1.setAdminStateUp(true)
            store update pool2

            Then("The actor should receive both updates")
            observer.getOnNextEvents.get(0)
                .asInstanceOf[Pool] shouldBeDeviceOf pool1
            observer.getOnNextEvents.get(1)
                .asInstanceOf[Pool] shouldBeDeviceOf pool2
        }

        scenario("Actor receives device deletion") {
            Given("A pool")
            val pool = createPool()
            store create pool

            When("Subscribing to the pool via the actor")
            actor ! Subscribe(pool.getId, classTag[Pool])

            And("The pool is deleted")
            store.delete(classOf[Topology.Pool], pool.getId)

            Then("The actor should receive both updates")
            observer.getOnNextEvents.get(0)
                .asInstanceOf[Pool] shouldBeDeviceOf pool
            observer.getOnCompletedEvents should not be empty
        }

        scenario("Actor receives updates from multiple devices") {
            Given("Two pool")
            val pool1 = createPool()
            val pool2 = createPool()
            store.multi(Seq(CreateOp(pool1), CreateOp(pool2)))

            When("Subscribing to the first pool via the actor")
            actor ! Subscribe(pool1.getId, classTag[Pool])

            And("Subscribing to the second pool via the actor")
            actor ! Subscribe(pool2.getId, classTag[Pool])

            Then("The actor should receive both updates")
            observer.getOnNextEvents.get(0)
                .asInstanceOf[Pool] shouldBeDeviceOf pool1
            observer.getOnNextEvents.get(1)
                .asInstanceOf[Pool] shouldBeDeviceOf pool2

            When("The first pool is deleted")
            store.delete(classOf[Topology.Pool], pool1.getId)

            Then("The actor should receive the deletion")
            observer.getOnCompletedEvents should have size 1

            When("The second pool is updated")
            val pool3 = pool2.setAdminStateUp(true)
            store update pool3

            Then("The actor should receive the update")
            observer.getOnNextEvents.get(2)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool3
        }
    }

    feature("The actor can unsubscribe") {
        scenario("Subscription to a single device") {
            Given("A pool")
            val pool1 = createPool()
            store create pool1

            When("Subscribing to the pool via the actor")
            actor ! Subscribe(pool1.getId, classTag[Pool])

            And("Unsubscribing from the pool")
            actor ! Unsubscribe(pool1.getId, classTag[Pool])

            And("Updating the pool")
            val pool2 = pool1.setAdminStateUp(true)
            store update pool2

            Then("The actor received only one notification")
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool1
        }

        scenario("Subscription to multiple devices") {
            Given("Two pools")
            val pool1 = createPool()
            val pool2 = createPool()
            store.multi(Seq(CreateOp(pool1), CreateOp(pool2)))

            When("Subscribing to the pools via the actor")
            actor ! Subscribe(pool1.getId, classTag[Pool])
            actor ! Subscribe(pool2.getId, classTag[Pool])

            And("Unsubscribing from the first pool")
            actor ! Unsubscribe(pool1.getId, classTag[Pool])

            And("Updating both pools")
            val pool3 = pool1.setAdminStateUp(true)
            val pool4 = pool2.setAdminStateUp(true)
            store.multi(Seq(UpdateOp(pool3), UpdateOp(pool4)))

            Then("The actor received three notifications")
            observer.getOnNextEvents should have size 3
            observer.getOnNextEvents.get(0)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool1
            observer.getOnNextEvents.get(1)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool2
            observer.getOnNextEvents.get(2)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool4
        }

        scenario("Actor unsubscribes on stop") {
            Given("A pool")
            val pool1 = createPool()
            store create pool1

            When("Subscribing to the pool via the actor")
            actor ! Subscribe(pool1.getId, classTag[Pool])

            And("The actor is stopped")
            actorSystem stop actor

            And("Updating the pool")
            val pool2 = pool1.setAdminStateUp(true)
            store update pool2

            Then("The actor received only one notification")
            observer.getOnNextEvents should have size 1
            observer.getOnNextEvents.get(0)
                    .asInstanceOf[Pool] shouldBeDeviceOf pool1
        }
    }
}
