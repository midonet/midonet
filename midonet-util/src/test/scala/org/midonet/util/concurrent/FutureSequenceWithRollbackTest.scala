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

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

@RunWith(classOf[JUnitRunner])
class FutureSequenceWithRollbackTest extends FeatureSpec
                                             with Matchers {

    private val timeout = Duration.Inf
    private val log = Logger(LoggerFactory.getLogger(getClass))

    feature("Successful execution and complete rollback") {
        scenario("single task") {
            val exec = new FutureSequenceWithRollback("test sequence", log)
            val task = Mockito.mock(classOf[FutureTaskWithRollback])
            Mockito.when(task.execute()).thenReturn(Future.successful(true))
            Mockito.when(task.rollback()).thenReturn(Future.successful(true))
            exec.add(task)

            Await.result(exec.execute(), timeout)

            Mockito.verify(task, Mockito.times(1)).execute()
            Mockito.verify(task, Mockito.times(0)).rollback()

            Await.result(exec.rollback(), timeout)
            Mockito.verify(task, Mockito.times(1)).execute()
            Mockito.verify(task, Mockito.times(1)).rollback()
        }

        scenario("multiple tasks") {
            val exec = new FutureSequenceWithRollback("test sequence", log)
            val Count = 5
            val tasks = for (i <- 0 to Count) yield {
                val task = Mockito.mock(classOf[FutureTaskWithRollback])
                Mockito.when(task.execute()).thenReturn(Future.successful(true))
                Mockito.when(task.rollback())
                    .thenReturn(Future.successful(true))
                task
            }

            tasks.foreach(exec.add)

            Await.result(exec.execute(), timeout)

            val inOrder = Mockito.inOrder(tasks: _*)
            tasks.foreach(inOrder.verify(_, Mockito.times(1)).execute())

            Await.result(exec.rollback(), timeout)
            tasks.reverse
                .foreach(inOrder.verify(_, Mockito.times(1)).rollback())
        }

        scenario("no tasks") {
            val exec = new FutureSequenceWithRollback("test sequence", log)

            Await.result(exec.execute(), timeout)

            Await.result(exec.rollback(), timeout)
        }
    }

    feature("Failure rollback") {
        def runWithFailedTask(failurePos: Int, count: Int): Unit = {
            val exec = new FutureSequenceWithRollback("test sequence", log)
            val tasks = for (i <- 0 to count) yield {
                val task = Mockito.mock(classOf[FutureTaskWithRollback])
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

            tasks.foreach(exec.add)

            val v = Await.ready(exec.execute(), timeout).value
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

            Await.result(exec.rollback(), timeout)
            for (i <- count-1 to 0 by -1) {
                if (i <= failurePos) {
                    inOrder.verify(tasks(i), Mockito.times(1)).rollback()
                } else {
                    Mockito.verify(tasks(i), Mockito.times(0)).rollback()
                }
            }

        }

        scenario("First task failed") {
            runWithFailedTask(0, 5)
        }

        scenario("Second task failed") {
            runWithFailedTask(1, 5)
        }

        scenario("Third task failed") {
            runWithFailedTask(2, 5)
        }

        scenario("Forth task failed") {
            runWithFailedTask(3, 5)
        }

        scenario("Last task failed") {
            runWithFailedTask(4, 5)
        }
    }

    feature("TaskSequence can be reused") {

        scenario("Multiple successful executions") {
            val exec = new FutureSequenceWithRollback("test sequence", log)
            val NumTasks = 5
            val tasks = for (i <- 0 to NumTasks) yield {
                val task = Mockito.mock(classOf[FutureTaskWithRollback])
                Mockito.when(task.execute()).thenReturn(Future.successful(true))
                Mockito.when(task.rollback())
                    .thenReturn(Future.successful(true))
                task
            }
            tasks.foreach(exec.add)
            for (executionCount <- 1 to 5) {
                Await.result(exec.execute(), timeout)
                tasks.foreach(Mockito.verify(_, Mockito.times(executionCount))
                                  .execute())
            }
        }

        scenario("Execution after failure") {
            val exec = new FutureSequenceWithRollback("test sequence", log)
            val NumTasks = 5
            val tasks = for (i <- 0 to NumTasks) yield {
                val task = Mockito.mock(classOf[FutureTaskWithRollback])
                Mockito.when(task.execute()).thenReturn(Future.successful(true))
                Mockito.when(task.rollback())
                    .thenReturn(Future.successful(true))
                task
            }
            tasks.foreach(exec.add)

            val FailurePoint = 3
            Mockito.when(tasks(FailurePoint).execute())
                .thenReturn(Future.failed(new Exception))

            val v = Await.ready(exec.execute(), timeout).value
            v.isDefined shouldBe true
            v.get.isFailure shouldBe true

            val inOrder = Mockito.inOrder(tasks:_*)

            for (i <- 0 to NumTasks) {
                if (i <= FailurePoint) {
                    inOrder.verify(tasks(i), Mockito.times(1)).execute()
                } else {
                    Mockito.verify(tasks(i), Mockito.times(0)).execute()
                }
            }

            Await.result(exec.rollback(), timeout)
            for (i <- NumTasks-1 to 0 by -1) {
                if (i <= FailurePoint) {
                    inOrder.verify(tasks(i), Mockito.times(1)).rollback()
                } else {
                    Mockito.verify(tasks(i), Mockito.times(0)).rollback()
                }
            }

            Mockito.when(tasks(FailurePoint).execute())
                .thenReturn(Future.successful(true))

            Await.result(exec.execute(), timeout)
            for (i <- 0 to NumTasks) {
                if (i <= FailurePoint) {
                    Mockito.verify(tasks(i), Mockito.times(2)).execute()
                } else {
                    Mockito.verify(tasks(i), Mockito.times(1)).execute()
                }
            }
        }
    }
}