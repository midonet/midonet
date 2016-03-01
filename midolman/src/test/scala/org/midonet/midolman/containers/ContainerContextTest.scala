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

package org.midonet.midolman.containers

import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Promise, Await, Future}
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.{Matchers, GivenWhenThen, FlatSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.util.functors.makeRunnable

@RunWith(classOf[JUnitRunner])
class ContainerContextTest extends FlatSpec with Matchers with GivenWhenThen {

    val timeout = 5 seconds

    "Task" should "be processed immediately if queue is empty" in {
        Given("A single threaded executor")
        val executor = Executors.newSingleThreadExecutor()

        And("A container context")
        val context = new ContainerContext(UUID.randomUUID(), executor)

        When("Executing a task")
        val count = new AtomicInteger
        val future = context.execute {
            count.incrementAndGet()
            Future.successful("result")
        }

        Then("The task should complete")
        Await.result(future, timeout) shouldBe "result"
        count.get shouldBe 1

        And("The context should be deletable")
        context.isDeletable shouldBe true

        executor.shutdown()
    }

    "Task" should "wait if queue is busy" in {
        Given("A single threaded executor")
        val executor = Executors.newSingleThreadExecutor()

        And("A container context")
        val context = new ContainerContext(UUID.randomUUID(), executor)

        When("Executing a task that completes asynchronously")
        val count = new AtomicInteger
        val promise1 = Promise[String]()
        val future1 = context.execute {
            count.incrementAndGet()
            promise1.future
        }

        Then("The context should be busy")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 0

        When("Executing a second task")
        val promise2 = Promise[String]()
        val future2 = context.execute {
            count.incrementAndGet()
            promise2.future
        }

        Then("The task should be waiting")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 1

        When("Completing the first task")
        promise1.trySuccess("result-1")

        Then("The task should complete")
        Await.result(future1, timeout) shouldBe "result-1"
        count.get should be >= 1

        And("The second task should be executing")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 0

        When("Completing the second task")
        promise2.trySuccess("result-2")

        Then("The task should complete")
        Await.result(future2, timeout) shouldBe "result-2"
        count.get shouldBe 2

        And("The context should be deletable")
        context.isDeletable shouldBe true

        executor.shutdown()
    }

    "Context executor" should "be available to complete asynchronous tasks" in {
        Given("A single threaded executor")
        val executor = Executors.newSingleThreadExecutor()

        And("A container context")
        val context = new ContainerContext(UUID.randomUUID(), executor)

        When("Executing a task that completes asynchronously")
        val count = new AtomicInteger
        val promise = Promise[String]()
        val future = context.execute {
            count.incrementAndGet()
            promise.future
        }

        Then("The context should be busy")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 0

        When("Completing the first task on the executor")
        executor execute makeRunnable {
            promise.trySuccess("result")
        }

        Then("The task should complete")
        Await.result(future, timeout) shouldBe "result"
        count.get shouldBe 1

        And("The context should be deletable")
        context.isDeletable shouldBe true

        executor.shutdown()
    }

    "Container executor" should "be available to other containers" in {
        Given("A single threaded executor")
        val executor = Executors.newSingleThreadExecutor()

        And("Two container contexts")
        val context1 = new ContainerContext(UUID.randomUUID(), executor)
        val context2 = new ContainerContext(UUID.randomUUID(), executor)

        When("Executing a task that completes asynchronously in the first context")
        val count = new AtomicInteger
        val promise1 = Promise[String]()
        val future1 = context1.execute {
            count.incrementAndGet()
            promise1.future
        }

        Then("The context should be busy")
        context1.isBusy shouldBe true
        context1.queuedTasks shouldBe 0

        When("Executing a task that completes asynchronously in the second context")
        val future2 = context2.execute {
            count.incrementAndGet()
            Future.successful("result-2")
        }

        Then("The second task should complete")
        Await.result(future2, timeout) shouldBe "result-2"
        count.get shouldBe 2

        When("Completing the first task on the executor")
        executor execute makeRunnable {
            promise1.trySuccess("result-1")
        }

        Then("The first task should complete")
        Await.result(future1, timeout) shouldBe "result-1"
        count.get shouldBe 2

        And("The contexts should be deletable")
        context1.isDeletable shouldBe true
        context2.isDeletable shouldBe true

        executor.shutdown()
    }

    "Container context" should "catch exceptions" in {
        Given("A single threaded executor")
        val executor = Executors.newSingleThreadExecutor()

        And("A container context")
        val context = new ContainerContext(UUID.randomUUID(), executor)

        When("Executing a task that completes asynchronously")
        val count = new AtomicInteger
        val latch = new CountDownLatch(1)
        val future1 = context.execute {
            count.incrementAndGet()
            latch.await()
            throw new Exception("error-1")
        }

        And("Executing a second task")
        val promise2 = Promise[String]()
        val future2 = context.execute {
            count.incrementAndGet()
            promise2.future
        }

        Then("The context should be busy and one task should be waiting")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 1

        When("Continuing the first task")
        latch.countDown()

        Then("The task should complete")
        val e = intercept[Exception] {
            Await.result(future1, timeout)
        }
        e.getMessage shouldBe "error-1"
        count.get should be >= 1

        And("The second task should be executing")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 0

        When("Completing the second task")
        promise2.trySuccess("result-2")

        Then("The task should complete")
        Await.result(future2, timeout) shouldBe "result-2"
        count.get shouldBe 2

        And("The context should be deletable")
        context.isDeletable shouldBe true

        executor.shutdown()
    }

    "Container context" should "be reentrant" in {
        Given("A single threaded executor")
        val executor = Executors.newSingleThreadExecutor()

        And("A container context")
        val context = new ContainerContext(UUID.randomUUID(), executor)

        When("Executing a task that executes a second task")
        val count = new AtomicInteger
        val latch = new CountDownLatch(1)
        val promise = Promise[String]()

        val future1 = context.execute {
            count.incrementAndGet()
            val f = context.execute {
                count.incrementAndGet()
                promise.future
            }
            latch.await()
            Future.successful(f)
        }

        Then("The context should be busy and one task should be waiting")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 1

        When("Continuing the first task")
        latch.countDown()

        Then("The task should return the future for the seconds task")
        val future2 = Await.result(future1, timeout).asInstanceOf[Future[String]]
        count.get should be >= 1

        And("The second task should be executing")
        context.isBusy shouldBe true
        context.queuedTasks shouldBe 0

        When("Completing the second task")
        promise.trySuccess("result")

        Then("The future of the second task should complete")
        Await.result(future2, timeout) shouldBe "result"
        count.get shouldBe 2

        And("The context should be deletable")
        context.isDeletable shouldBe true

        executor.shutdown()
    }

}
