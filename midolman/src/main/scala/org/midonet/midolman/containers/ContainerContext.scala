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
import java.util.concurrent.ExecutorService

import scala.collection.mutable
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.util.control.NonFatal

import org.midonet.midolman.containers.ContainerContext.Task
import org.midonet.util.functors.makeRunnable

object ContainerContext {

    private case class Task(task: () => Future[Any]) {
        val promise = Promise[Any]()
    }

}

/**
  * Provides an execution queue for a container handler. All tasks performed
  * by a container must be serialized to ensure a consistent order of
  * operations. Furthermore, container operations may complete asynchronously,
  * for example after receiving data from storage, and for this reason they
  * return a future indicating when the operation has completed.
  *
  * This container context provides a single-threaded executor on which the
  * container operations are run, and a submission queue on which new tasks
  * are waiting for the previous one to complete. A new container operation
  * is not submitted to the executor until the future of the previous operation
  * has completed. This ensures that the executor can be shared with other
  * containers, and the executor is also available to complete asynchronous
  * tasks from the current operation.
  *
  * The class is thread-safe, such that container tasks can be submitted from
  * a different thread, typically the container service thread. Each submission
  * returns a future that completes when the submitted task has completed.
  *
  *      service thread          container thread            I/O thread
  *            |                        |                        |
  *         +-----+  context queue   +-----+                  +-----+
  *    ---> |C1.1 | ---------------> |C1.1 | ---------------> |C1.1 |
  *         +-----+ C1: [1]          +-----+                  |     |
  *            |    C2: []              |                     |     |
  *         +-----+                     |                     |     |
  *    ---> |C1.2 |                     |                     |     |
  *         +-----+ C1: [1, 2]          |                     |     |
  *            |    C2: []              |                     |     |
  *         +-----+                  +-----+ Thread available |     |
  *    ---> |C2.1 | ---------------> |C2.1 | to other         |     |
  *         +-----+ C1: [1, 2]       |     | containers       |     |
  *            |    C2: [1]          |     |                  |     |
  *            |    <--------------- |     |                  |     |
  *            |    C1: [1, 2]       +-----+                  |     |
  *            |    C2: []              |                     |     |
  *            |                     +-----+                  |     |
  *            |    <--------------- |C1.1 | <--------------- |     |
  *            |    C1: [2]          +-----+ Thread used to   +-----+
  *            |    C2: []           |C1.2 | complete C1.1       |
  *            |                     +-----+ not being blocked   |
  *            |                        |    by C1.2             |
  */
case class ContainerContext(id: UUID, executor: ExecutorService) {

    val ec = ExecutionContext.fromExecutor(executor)

    private val queue = new mutable.Queue[Task]
    @volatile private var busy = false

    /**
      * Executes a new task on the context executor. The task returns a future
      * that completes with the task. If the context queue is empty, the task
      * is submitted to the executor immediately, otherwise the task is added
      * to the context queue, to be executed after the last operation from the
      * queue. This ensures that the executor is free to take on later tasks
      * that do not affect the order of the container operations (these include
      * operations executed by other containers on the same executor, as well
      * as asynchronous completion tasks for this container).
      */
    def execute(fn: => Future[Any]): Future[Any] = this.synchronized {
        val task = Task(() => fn)
        if (busy) {
            queue.enqueue(task)
        } else {
            busy = true
            executeNow(task)
        }
        task.promise.future
    }

    /**
      * @return True if there are no tasks being executed for this context.
      */
    def isDeletable: Boolean = this.synchronized { !busy && queue.isEmpty }

    /**
      * @return True if the context is busy executing a task.
      */
    def isBusy: Boolean = busy

    /**
      * @return The number of tasks waiting in the queue.
      */
    def queuedTasks: Int = this.synchronized { queue.size }

    private def executeNow(task: Task): Unit = {
        executor.execute(makeRunnable {
            val future = try task.task()
            catch {
                case NonFatal(e) => Future.failed(e)
            }
            task.promise.tryCompleteWith(
                future.andThen {
                    case _ => ContainerContext.this.synchronized {
                        if (queue.isEmpty) {
                            busy = false
                        } else {
                            executeNow(queue.dequeue())
                        }
                    }
                }(ec))
        })
    }
}
