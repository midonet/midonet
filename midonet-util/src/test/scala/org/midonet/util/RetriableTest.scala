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

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

@RunWith(classOf[JUnitRunner])
class RetriableTest extends FeatureSpec with GivenWhenThen with Matchers {

    private val Retries = 3

    private val Log = Logger(LoggerFactory.getLogger("RetriableTest"))

    class TestableClosable extends Closeable {

        var closed = false

        override def close(): Unit = if (closed)
            throw new IllegalStateException
        else
            closed = true
    }

    // Needed since Mockito can't mock Scala's call by name
    class TestableRetriable extends ClosingRetriable {

        var calls = 0

        override def retry[T](retries: Int, log: Logger, message: String)
                             (retriable: => T): Either[Throwable, T] = {
            calls += 1
            super.retry(retries, log, message)(retriable)
        }
    }

    feature("Retry on different retriables") {
        scenario("Retrying on a successful function") {
            Given("A Retriable object")
            val mockedRetriable = new TestableRetriable

            When("Called on a successful function")
            val expected = "ok"
            val result = mockedRetriable.retry(Retries, Log, "ok") { "ok" }

            Then("The retriable is called only once")
            mockedRetriable.calls shouldBe 1
            And("The expected result is wrapped in the result")
            result.right.get shouldBe expected
        }

        scenario("Retrying on a failing function") {
            Given("A Retriable object")
            val mockedRetriable = new TestableRetriable

            When("Called on a failing function")
            val result = mockedRetriable.retry(Retries, Log, "ko") {
                throw new Exception
            }

            Then("The retriable is called all times")
            mockedRetriable.calls shouldBe Retries
            And("The result is an error")
            result.isLeft shouldBe true
        }
    }

    feature("Retry on different closing retriables") {
        scenario("Retrying on a successful closeable function") {
            Given("A ClosingRetriable object")
            val mockedRetriable = new TestableRetriable
            And("A closed closeable")
            val closeable = new TestableClosable
            closeable.closed shouldBe false

            When("Called on a successful function")
            val expected = "ok"
            val result = mockedRetriable.retryClosing(Retries, Log, "ok") (closeable) ("ok")

            Then("The retriable is called only once")
            mockedRetriable.calls shouldBe 1
            And("The expected result is wrapped in the result")
            result.right.get shouldBe expected
            And("The closeable was closed")
            closeable.closed shouldBe true
        }

        scenario("Retrying on a failing closeable function") {
            Given("A ClosingRetriable object")
            val mockedRetriable = new TestableRetriable
            And("A closed closeable")
            val closeable = new TestableClosable
            closeable.closed shouldBe false

            When("Called on a failing function")
            val result = mockedRetriable.retryClosing(Retries, Log, "ko")(closeable) {
                throw new Exception
            }

            Then("The retriable is called all times")
            mockedRetriable.calls shouldBe Retries
            And("The result is an error")
            result.isLeft shouldBe true
            And("The closeable was closed")
            closeable.closed shouldBe true
        }

    }

}
