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

package org.midonet.midolman.vpp

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class VppExecutorTest extends FeatureSpec with Matchers with GivenWhenThen {

    private class TestableVppExecutor(override val receive: Receive)
        extends VppExecutor {

        def !(message: Any): Future[Any] = send(message)

        override def doStart(): Unit = {
            notifyStarted()
        }

        override def doStop(): Unit = {
            super.doStop()
            notifyStopped()
        }
    }

    feature("VPP executor handles messages") {
        scenario("VPP executor starts and stops") {
            Given("A VPP executor")
            val vpp = new TestableVppExecutor(VppExecutor.Nothing)

            When("Starting the VPP executor")
            vpp.startAsync().awaitRunning()

            Then("The VPP executor should be running")
            vpp.isRunning shouldBe true

            When("Stopping the VPP executor")
            vpp.stopAsync().awaitTerminated()

            Then("The VPP executor should be stopped")
            vpp.isRunning shouldBe false
        }

        scenario("VPP executor manages unhandled messages") {
            Given("A VPP executor")
            val vpp = new TestableVppExecutor(VppExecutor.Nothing)
            vpp.startAsync().awaitRunning()

            When("Sending a message")
            val future = vpp ! 1

            Then("The message should execute")
            intercept[UnsupportedOperationException] { future.await() }

            vpp.stopAsync().awaitTerminated()
        }

        scenario("VPP executor manages handled messages") {
            Given("A message handler")
            val handler: Receive = {
                case n: Int => Future.successful(2 * n)
                case s: String => Future.successful(s.reverse)
            }

            And("A VPP executor")
            val vpp = new TestableVppExecutor(handler)
            vpp.startAsync().awaitRunning()

            When("Sending an integer")
            val future1 = vpp ! 11

            Then("The message should execute")
            future1.await() shouldBe 22

            When("Sending a string")
            val future2 = vpp ! "test"

            Then("The message should execute")
            future2.await() shouldBe "tset"

            When("Sending an unhandled message")
            val future3 = vpp ! new Object

            Then("The message should execute")
            intercept[UnsupportedOperationException] { future3.await() }

            vpp.stopAsync().awaitTerminated()
        }

        scenario("VPP executor does not handle messages if not started") {
            Given("A VPP executor")
            val vpp = new TestableVppExecutor(VppExecutor.Nothing)

            When("Sending a message")
            val future = vpp ! 1

            Then("The message should fail")
            intercept[IllegalStateException] { future.await() }
        }

        scenario("Message execution should be serialized") {
            Given("A message handler where messages complete on demand")
            import scala.concurrent.ExecutionContext.Implicits.global

            @volatile var result = Seq[Int]()
            val handler: Receive = {
                case value: Int =>
                    Future {
                        result = result :+ value
                        value
                    }
            }

            And("A VPP executor")
            val vpp = new TestableVppExecutor(handler)
            vpp.startAsync().awaitRunning()

            When("Sending several messages")
            val futures = for (index <- 1 until 100) yield {
                vpp ! index
            }

            Then("The futures should complete")
            Future.sequence(futures).await()

            And("The messages should execute in sequence")
            result should contain theSameElementsInOrderAs (1 until 100)
        }

        scenario("VPP executor manages mis-behaving handlers") {
            Given("A message handler")
            val e = new Exception()
            val handler: Receive = {
                case _ => throw e
            }

            And("A VPP executor")
            val vpp = new TestableVppExecutor(handler)
            vpp.startAsync().awaitRunning()

            When("Sending a message")
            val future = vpp ! 0

            Then("The executor should handle the exception")
            intercept[Exception] { future.await() } shouldBe e
        }
    }

}
