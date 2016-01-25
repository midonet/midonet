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

package org.midonet.util.io

import java.io.{File, PrintWriter}
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.duration._

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import org.midonet.util.concurrent._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class TailerTest extends FlatSpec with Matchers with GivenWhenThen {

    val timeout = 5 seconds

    "Single tailer" should "read data from FIFO file" in {
        Given("A FIFO file")
        val fileName = s"/tmp/test-fifo-${UUID.randomUUID()}.log"
        Runtime.getRuntime.exec(s"mkfifo -m 0644 $fileName")
        val file = new File(fileName)

        And("A scheduled executor")
        val executor = Executors.newSingleThreadScheduledExecutor()

        And("A tailer observer")
        val observer = new TestAwaitableObserver[String]

        And("A file tailer")
        val tailer = new Tailer(file, executor, observer, 50, TimeUnit.MILLISECONDS)

        When("The tailer starts")
        tailer.start()
        Thread.sleep(500)

        When("Writing to the file 1000 lines")
        val writer = new PrintWriter(fileName, "UTF-8")
        for (i <- 0 until 1000) {
            writer.println(s"Test line $i")
        }
        writer.flush()

        Then("The observer should receive 1000 notifications")
        observer.awaitOnNext(1000, timeout)

        When("Writing to the file 1000 lines")
        for (i <- 1000 until 2000) {
            writer.println(s"Test line $i")
        }
        writer.flush()

        Then("The observer should receive 2000 notifications")
        observer.awaitOnNext(2000, timeout)

        When("Writing to the file 1000 lines")
        for (i <- 2000 until 3000) {
            writer.println(s"Test line $i")
        }
        writer.close()

        Then("The observer should receive 3000 notifications")
        observer.awaitOnNext(3000, timeout)

        tailer.close().await(timeout)
        FileUtils.deleteQuietly(file)
        executor.shutdownNow()
    }

    "Multiple tailers" should "read data from FIFO files" in {
        Given("Three FIFO file")
        val fileName1 = s"/tmp/test-fifo-${UUID.randomUUID()}.log"
        val fileName2 = s"/tmp/test-fifo-${UUID.randomUUID()}.log"
        val fileName3 = s"/tmp/test-fifo-${UUID.randomUUID()}.log"
        Runtime.getRuntime.exec(s"mkfifo -m 0644 $fileName1")
        Runtime.getRuntime.exec(s"mkfifo -m 0644 $fileName2")
        Runtime.getRuntime.exec(s"mkfifo -m 0644 $fileName3")
        val file1 = new File(fileName1)
        val file2 = new File(fileName2)
        val file3 = new File(fileName3)

        And("A scheduled executor")
        val executor = Executors.newSingleThreadScheduledExecutor()

        And("Three tailer observers")
        val observer1 = new TestAwaitableObserver[String]
        val observer2 = new TestAwaitableObserver[String]
        val observer3 = new TestAwaitableObserver[String]

        And("Three file tailers")
        val tailer1 = new Tailer(file1, executor, observer1, 100, TimeUnit.MILLISECONDS,
                                 0xFFFF, 10)
        val tailer2 = new Tailer(file2, executor, observer2, 100, TimeUnit.MILLISECONDS,
                                 0xFFFF, 10)
        val tailer3 = new Tailer(file3, executor, observer3, 100, TimeUnit.MILLISECONDS,
                                 0xFFFF, 10)

        When("The tailers start")
        tailer1.start()
        tailer2.start()
        tailer3.start()
        Thread.sleep(500)

        When("Writing to the file 1000 lines")
        val writer1 = new PrintWriter(fileName1, "UTF-8")
        val writer2 = new PrintWriter(fileName2, "UTF-8")
        val writer3 = new PrintWriter(fileName3, "UTF-8")
        for (i <- 0 until 1000) {
            writer1.println(s"Test line $i")
            writer2.println(s"Test line $i")
            writer3.println(s"Test line $i")
        }
        writer1.flush()
        writer2.flush()
        writer3.flush()

        Then("The observers should receive 1000 notifications")
        observer1.awaitOnNext(1000, timeout)
        observer2.awaitOnNext(1000, timeout)
        observer3.awaitOnNext(1000, timeout)

        When("Writing to the file 1000 lines")
        for (i <- 1000 until 2000) {
            writer1.println(s"Test line $i")
            writer2.println(s"Test line $i")
            writer3.println(s"Test line $i")
        }
        writer1.flush()
        writer2.flush()
        writer3.flush()

        Then("The observers should receive 2000 notifications")
        observer1.awaitOnNext(2000, timeout)
        observer2.awaitOnNext(2000, timeout)
        observer3.awaitOnNext(2000, timeout)

        When("Writing to the file 1000 lines")
        for (i <- 2000 until 3000) {
            writer1.println(s"Test line $i")
            writer2.println(s"Test line $i")
            writer3.println(s"Test line $i")
        }
        writer1.close()
        writer2.close()
        writer3.close()

        Then("The observers should receive 3000 notifications")
        observer1.awaitOnNext(3000, timeout)
        observer2.awaitOnNext(3000, timeout)
        observer3.awaitOnNext(3000, timeout)

        tailer1.close().await(timeout)
        tailer2.close().await(timeout)
        tailer3.close().await(timeout)
        FileUtils.deleteQuietly(file1)
        FileUtils.deleteQuietly(file2)
        FileUtils.deleteQuietly(file3)
        executor.shutdownNow()
    }
}
