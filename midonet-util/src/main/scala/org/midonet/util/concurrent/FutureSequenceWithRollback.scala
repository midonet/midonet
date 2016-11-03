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

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

/**
  * FutureTaskWithRollback
  *
  * Trait for wrapping a task that returns a future for the action it performs
  * and supports rollback.
  */
trait FutureTaskWithRollback {

    def name: String

    @throws[Exception]
    def execute(): Future[Any]

    @throws[Exception]
    def rollback(): Future[Any]
}

object FutureTaskWithRollback {

    def apply(taskName: String,
              executeCallback: () => Future[Any],
              undoCallback: () => Future[Any]): FutureTaskWithRollback =
        new FutureTaskWithRollback {

            override val name: String = taskName

            override def execute() = executeCallback()

            override def rollback() = undoCallback()
        }
}

object FutureSequenceWithRollback {

    case class IgnoredFailure(throwable: Throwable)

    private trait Operation {

        def next(counter: Int): Int

        def initialPosition(current: Int): Int

        def ignoreFailures: Boolean

        def apply(task: FutureTaskWithRollback): Future[Any]
    }

    private case object Execute extends Operation {

        override val toString = "execute"

        override def initialPosition(current: Int) = 0

        override def next(counter: Int) = counter + 1

        override val ignoreFailures = false

        override def apply(task: FutureTaskWithRollback) = task.execute()
    }

    private case object Rollback extends Operation {

        override val toString = "rollback"

        override def initialPosition(current: Int) = current - 1

        override def next(counter: Int) = counter - 1

        override val ignoreFailures = true

        override def apply(task: FutureTaskWithRollback) = task.rollback()
    }
}

/**
  * FutureSequenceWithRollback
  *
  * Allows to perform a sequence of [[FutureTaskWithRollback]] operations.
  * Once composed with [[add]], can be executed or rolled back multiple times.
  * Exposes the FutureTaskWithRollback trait so it can be used as a task in
  * other sequences.
  *
  * @param name Description for this sequence (used in logs)
  * @param ec Execution context
  */
class FutureSequenceWithRollback(val name: String, log: Logger)
                                (implicit ec: ExecutionContext)
    extends FutureTaskWithRollback {

    import FutureSequenceWithRollback._

    private val steps = ArrayBuffer.empty[FutureTaskWithRollback]
    private var position = 0

    def add(task: FutureTaskWithRollback): Unit = steps += task

    override def execute(): Future[Any] = executeOp(Execute)

    override def rollback(): Future[Any] = executeOp(Rollback)

    private def executeOp(op: Operation): Future[Any] = {
        if (steps.nonEmpty) {
            position = op.initialPosition(position)
            log.debug(s"Started to $op $name")
            val promise = Promise[Unit]
            executeChained(op) onComplete {
                case Success(_) =>
                    log.debug(s"Completed $op of $name")
                    promise.success(())
                case Failure(err) =>
                    log.warn(s"Failed to $op $name: $err")
                    promise.failure(err)
            }
            promise.future
        } else {
            Future.successful("Nothing to do")
        }
    }

    private def executeChained(op: Operation): Future[Any] = {
        if (position >=0 && position < steps.size) {
            try {
                val task = steps(position)
                position = op.next(position)
                log.debug(s"Sequence $name: $op ${task.name}")
                op(task) recover {
                    case NonFatal(err) =>
                        log.warn(s"Sequence $name: $op failed " +
                                 s"${task.name}: $err")
                        if (op.ignoreFailures) {
                            /* succeed this task's future with an instance
                             * of IgnoredFailure(...)
                             */
                            IgnoredFailure(err)
                        } else {
                            throw err
                        }
                } flatMap { _ =>
                    log.debug(s"Sequence $name: completed $op ${task.name}")
                    executeChained(op)
                }
            } catch {
                case NonFatal(err) => Future.failed(err)
            }
        } else {
            Future.successful(())
        }
    }
}
