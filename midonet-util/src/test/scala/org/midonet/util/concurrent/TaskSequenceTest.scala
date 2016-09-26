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

package org.midonet.util.concurrent

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.util.Success

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TaskSequenceTest extends FeatureSpec
                           with Matchers {

    val Timeout = Duration.Inf

    feature("Successful execution and complete rollback") {
        scenario("single task") {
            val exec = new TaskSequence("test sequence")
            val task = Mockito.mock(classOf[Task])
            Mockito.when(task.execute()).thenReturn(Future.successful(true))
            Mockito.when(task.rollback()).thenReturn(Future.successful(true))
            exec.add(task)

            Await.result(exec.execute(), Timeout)

            Mockito.verify(task, Mockito.times(1)).execute()
            Mockito.verify(task, Mockito.times(0)).rollback()

            Await.result(exec.rollback(), Timeout)
            Mockito.verify(task, Mockito.times(1)).execute()
            Mockito.verify(task, Mockito.times(1)).rollback()
        }

        scenario("multiple tasks") {
            val exec = new TaskSequence("test sequence")
            val Count = 5
            val tasks = for (i <- 0 to Count) yield {
                val task = Mockito.mock(classOf[Task])
                Mockito.when(task.execute()).thenReturn(Future.successful(true))
                Mockito.when(task.rollback())
                    .thenReturn(Future.successful(true))
                task
            }

            tasks.foreach(exec.add(_))

            Await.result(exec.execute(), Timeout)

            val inOrder = Mockito.inOrder(tasks: _*)
            tasks.foreach(inOrder.verify(_, Mockito.times(1)).execute())

            Await.result(exec.rollback(), Timeout)
            tasks.reverse
                .foreach(inOrder.verify(_, Mockito.times(1)).rollback())
        }

        scenario("no tasks") {
            val exec = new TaskSequence("test sequence")

            Await.result(exec.execute(), Timeout)

            Await.result(exec.rollback(), Timeout)
        }
    }
    feature("Failure rollback") {
        def checkFailedTask(failurePos: Int, count: Int): Unit = {
            val exec = new TaskSequence("test sequence")
            val tasks = for (i <- 0 to count) yield {
                val task = Mockito.mock(classOf[Task])
                if (i != failurePos) {
                    Mockito.when(task.execute())
                        .thenReturn(Future.successful(true))
                } else {
                    Mockito.when(task.execute()).thenThrow(new Exception())
                }

                Mockito.when(task.rollback())
                    .thenReturn(Future.successful(true))
                task
            }

            tasks.foreach(exec.add(_))

            val v = Await.ready(exec.execute(), Timeout).value
            v.isDefined shouldBe true
            v.get.isFailure shouldBe true

            val inOrder = Mockito.inOrder(tasks:_*)

            for (i <- 0 to count) {
                if (i <= failurePos) {
                    inOrder.verify(tasks(i), Mockito.times(1)).execute()
                } else {
                    Mockito.verify(tasks(i), Mockito.times(0)).execute()
                }
            }

            Await.result(exec.rollback(), Timeout)
            for (i <- count-1 to 0 by -1) {
                if (i < failurePos) {
                    inOrder.verify(tasks(i), Mockito.times(1)).rollback()
                } else {
                    Mockito.verify(tasks(i), Mockito.times(0)).rollback()
                }
            }

        }

        scenario("First task failed") {
            checkFailedTask(0, 5)
        }

        scenario("Second task failed") {
            checkFailedTask(1, 5)
        }

        scenario("Third task failed") {
            checkFailedTask(2, 5)
        }

        scenario("Forth task failed") {
            checkFailedTask(3, 5)
        }

        scenario("Last task failed") {
            checkFailedTask(4, 5)
        }

    }
}