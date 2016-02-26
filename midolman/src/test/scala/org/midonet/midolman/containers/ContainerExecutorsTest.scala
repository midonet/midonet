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

import java.util.concurrent.{TimeUnit, CountDownLatch}

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.{Matchers, GivenWhenThen, FlatSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.config.ContainerConfig
import org.midonet.util.functors.makeRunnable

@RunWith(classOf[JUnitRunner])
class ContainerExecutorsTest extends FlatSpec with GivenWhenThen with Matchers {

    "Pool" should "create executors for config set to zero" in {
        Given("A configuration")
        val config = new ContainerConfig(ConfigFactory.parseString(
            s"""
               |agent.containers.thread_count : 0
               |agent.containers.shutdown_grace_time : 30 s
            """.stripMargin), ConfigFactory.empty())

        And("A container executor pool")
        val executors = new ContainerExecutors(config)

        Then("The executor starts a thread for each processor")
        executors.count shouldBe 1

        executors.shutdown()
    }

    "Pool" should "create executors for positive config" in {
        Given("A configuration")
        val config = new ContainerConfig(ConfigFactory.parseString(
            s"""
               |agent.containers.thread_count : 6
               |agent.containers.shutdown_grace_time : 30 s
            """.stripMargin), ConfigFactory.empty())

        And("A container executor pool")
        val executors = new ContainerExecutors(config)

        Then("The executor starts a thread for each processor")
        executors.count shouldBe 6

        executors.shutdown()
    }

    "Pool" should "return an executor" in {
        Given("A configuration")
        val config = new ContainerConfig(ConfigFactory.parseString(
            s"""
               |agent.containers.thread_count : 1
               |agent.containers.shutdown_grace_time : 30 s
            """.stripMargin), ConfigFactory.empty())

        And("A container executor pool")
        val executors = new ContainerExecutors(config)

        Then("The executor should return an executor")
        val executor = executors.nextExecutor()

        And("The executor should execute a runnable")
        val latch = new CountDownLatch(1)
        executor.execute(makeRunnable { latch.countDown() })
        latch.await(1, TimeUnit.SECONDS) shouldBe true

        When("The executors are shut down")
        executors.shutdown()

        Then("The executor is shut down")
        executor.isShutdown shouldBe true
    }

}
