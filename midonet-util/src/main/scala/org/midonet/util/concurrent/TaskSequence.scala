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
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import org.midonet.util.logging.Logging

/**
  * TaskSequence
  */

trait Task {
    def name: String

    @throws[Exception]
    def execute(): Future[Any]

    @throws[Exception]
    def rollback(): Future[Any]
}

object Task {
    def apply(taskName: String,
              executeCallback: () => Future[Any],
              undoCallback: () => Future[Any]): Task = new Task {
        override val name: String = taskName
        override def execute() = executeCallback()
        override def rollback() = undoCallback()
    }
}

class TaskSequence(val name: String)(implicit ec: ExecutionContext)
    extends Task
            with Logging {

    private val steps = ArrayBuffer.empty[Task]
    private var position = 0

    def add(task: Task): Unit = steps += task

    override def execute(): Future[Any] = {
        position = 0
        executeOp("execute",
                  1,
                  _.execute())
    }

    override def rollback(): Future[Any] = {
        // skip failed task (must do it's own cleanup)
        position -= 1
        executeOp("rollback",
                  -1,
                  _.rollback())
    }

    private def executeOp(operation: String,
                          delta: Int,
                          action: Task => Future[Any]): Future[Any] = {
        if (steps.nonEmpty) {
            log debug s"Started to $operation $name"
            val promise = Promise[Unit]
            executeChained(operation, delta, action) onComplete {
                case Success(_) =>
                    log debug s"Completed $operation of $name"
                    promise.success(())
                case Failure(err) =>
                    log warn s"Failed to $operation $name: $err"
                    promise.failure(err)
            }
            promise.future
        } else {
            Future.successful("Nothing to do")
        }
    }

    private def executeChained(operation: String,
                               delta: Int,
                               action: Task => Future[Any]): Future[Any] = {
        if (position >=0 && position < steps.size) {
            try {
                val task = steps(position)
                log trace s"Sequence $name: $operation ${task.name}"
                action(task) recover {
                    case NonFatal(err) =>
                        log warn s"Sequence $name: $operation failed " +
                                 s"${task.name}: $err"
                        throw err
                } flatMap { _ =>
                    log trace s"Sequence $name: completed $operation " +
                              s"${task.name}"
                    position += delta
                    executeChained(operation, delta, action)
                }
            } catch {
                case NonFatal(err) => Future.failed(err)
            }
        } else {
            Future.successful(())
        }
    }
}
