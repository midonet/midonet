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
package org.midonet.util

import java.io.Closeable

import scala.collection.mutable
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.{Logger, LoggerFactory}

@RunWith(classOf[JUnitRunner])
class RetriableTest extends FeatureSpec with GivenWhenThen with Matchers {

    private val Log = LoggerFactory.getLogger("RetriableTest")

    class TestableClosable extends Closeable {

        var closed = false

        override def close(): Unit = if (closed)
            throw new IllegalStateException
        else
            closed = true
    }

    // Needed since Mockito can't mock Scala's call by name
    trait TestableRetriable extends Retriable {

        override def maxRetries = 3

        var retries = 0

        protected override def handleRetry[T](e: Throwable, r: Int, log: Logger,
                                              message: String): Unit = {
            retries += 1
        }
    }

    class TestableAwaitRetriable extends TestableRetriable with AwaitRetriable {

        override def interval: Duration = 100 millis

        var awaits = mutable.MutableList.empty[Long]

        override def await(timeout: Long) = {
            awaits += timeout
        }
    }

    class TestableExponentialBackoffRetriable extends TestableRetriable
                with ExponentialBackoffRetriable {
        override def maxRetries: Int = 6
        override def interval: Duration = 100 millis
        override def maxDelay: Duration = 2000 millis

        var awaits = mutable.HashMap.empty[Int, Int]

        override def backoffTime(attempt: Int): Int = {
            val backoff = super.backoffTime(attempt)
            awaits += attempt -> backoff
            backoff
        }

    }

    feature("Retry on different retriables") {
        scenario("Retrying on a successful function") {
            Given("A Retriable object")
            val mockedRetriable = new TestableRetriable with ImmediateRetriable

            When("Called on a successful function")
            val expected = "ok"
            val result = mockedRetriable.retry(Log, "ok") { "ok" }

            Then("The retriable is called only once")
            mockedRetriable.retries shouldBe 0
            And("The expected result is wrapped in the result")
            result shouldBe expected
        }

        scenario("Retrying on a failing function") {
            Given("A Retriable object")
            val mockedRetriable = new TestableRetriable with ImmediateRetriable

            When("Called on a failing function")
            val e = intercept[Exception] {
                mockedRetriable.retry(Log, "ko") {
                    throw new Exception
                }
            }

            Then("The retriable is called all times")
            mockedRetriable.retries shouldBe mockedRetriable.maxRetries

            And("The result is an error")
            e should not be null
        }
    }

    feature("Retry on different closing retriables") {
        scenario("Retrying on a successful closeable function") {
            Given("A ClosingRetriable object")
            val mockedRetriable = new TestableRetriable
                                      with ClosingRetriable
                                      with ImmediateRetriable {}
            And("A closed closeable")
            val closeable = new TestableClosable
            closeable.closed shouldBe false

            When("Called on a successful function")
            val expected = "ok"
            val result = mockedRetriable.retryClosing(Log, "ok") (closeable) ("ok")

            Then("The retriable is called only once")
            mockedRetriable.retries shouldBe 0

            And("The expected result is wrapped in the result")
            result shouldBe expected

            And("The closeable was closed")
            closeable.closed shouldBe true
        }

        scenario("Retrying on a failing closeable function") {
            Given("A ClosingRetriable object")
            val mockedRetriable = new TestableRetriable
                                      with ClosingRetriable
                                      with ImmediateRetriable {}
            And("A closed closeable")
            val closeable = new TestableClosable
            closeable.closed shouldBe false

            When("Called on a failing function")
            val e = intercept[Exception] {
                mockedRetriable.retryClosing(Log, "ko")(closeable) {
                    throw new Exception
                }
            }

            Then("The retriable is called all times")
            mockedRetriable.retries shouldBe mockedRetriable.maxRetries

            And("The result is an error")
            e should not be null

            And("The closeable was closed")
            closeable.closed shouldBe true
        }

    }

    feature("Retry on an awaitable retriable") {
        scenario("Awaiting a certain amount of time") {
            Given("An awaitable retriable")
            val mockedRetriable = new TestableAwaitRetriable

            When("Called on a failing function")
            intercept[Exception]{
                mockedRetriable.retry(Log, "ko") {
                    throw new Exception
                }
            }

            Then("The retriable is called all times")
            mockedRetriable.retries shouldBe mockedRetriable.maxRetries

            And("The await times should be the defined timeout")
            for (await <- mockedRetriable.awaits) {
                await shouldBe mockedRetriable.interval.toMillis
            }
        }
    }

    feature("Retry on an exponential backoff retriable") {
        scenario("Waiting a random amount of time exponentially increasing") {
            Given("An awaitable retriable")
            val mockedRetriable = new TestableExponentialBackoffRetriable

            When("Called on a failing function")
            intercept[Exception] {
                mockedRetriable.retry(Log, "ko") {
                    throw new Exception
                }
            }

            Then("The retriable is called all times")
            mockedRetriable.retries shouldBe 6

            And("The await times should be the exponentially increasing up to a ceiling")
            val expected = Map(
                0 -> 100, 1 -> 200, 2 -> 400, 3 -> 800, 4 -> 1600, 5 -> 2000)
            mockedRetriable.awaits shouldBe expected
        }
    }
}
