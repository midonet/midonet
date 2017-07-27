/*
 * Copyright 2015 Midokura SARL
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

import scala.collection.JavaConversions._
import java.util.{Collection, LinkedList, List => JList}
import java.util.concurrent._

import com.google.common.util.concurrent.SettableFuture

class SameThreadButAfterExecutorService extends ScheduledExecutorService {
    var running = false
    @volatile var _shutdown = false

    val queue = new LinkedList[Runnable]()

    override def shutdown(): Unit = { _shutdown = true}
    override def shutdownNow(): JList[Runnable] = {
        _shutdown = true
        queue
    }

    override def isShutdown(): Boolean = _shutdown
    override def isTerminated(): Boolean = _shutdown
    override def awaitTermination(timeout: Long,
                                  unit: TimeUnit): Boolean = _shutdown


    override def submit[T](task: Callable[T]): Future[T] = this.synchronized {
        val future: SettableFuture[T] = SettableFuture.create()
        queue.add(new Runnable() {
                      override def run() = {
                          try {
                              future.set(task.call())
                          } catch {
                              case e: Exception => future.setException(e)
                          }
                      }
                  })
        if (!running) {
            running = true
            var r = queue.poll()
            while (r != null && !_shutdown) {
                r.run()
                r = queue.poll()
            }
            running = false
        }
        future
    }

    override def submit[T](task: Runnable,
                           result: T): Future[T] = {
        submit(new Callable[T]() {
                   override def call(): T = {
                       task.run()
                       result
                   }
               })
    }

    override def submit(task: Runnable): Future[_] = {
        submit(task, true)
    }

    override def execute(command: Runnable): Unit = {
        submit(command)
    }

    override def invokeAll[T](tasks: Collection[_ <: Callable[T]])
            : JList[Future[T]] = {
        tasks map { submit(_) } toList
    }


    override def invokeAll[T](tasks: Collection[_ <: Callable[T]],
                              timeout: Long, unit: TimeUnit)
            : JList[Future[T]] = {
        invokeAll(tasks)
    }

    override def invokeAny[T](tasks: Collection[_ <: Callable[T]]): T = {
        var ee: ExecutionException = null
        invokeAll(tasks).map((r: Future[T]) =>
                                 try {
                                     Some(r.get)
                                 } catch {
                                     case e: ExecutionException => {
                                         ee = e
                                         None
                                     }
                                 })
            .filter({ (t: Option[T]) => t.isDefined })
            .head.getOrElse({ throw ee })
    }

    override def invokeAny[T](tasks: Collection[_ <: Callable[T]],
                              timeout: Long, unit: TimeUnit): T = {
        invokeAny(tasks)
    }

    override def scheduleAtFixedRate(command: Runnable,
                                     initialDelay: Long, period: Long,
                                     unit: TimeUnit): ScheduledFuture[_] =
        throw new NotImplementedError()

    override def schedule(command: Runnable, delay: Long,
                          unit: TimeUnit): ScheduledFuture[_] =
        throw new NotImplementedError()

    override def schedule[V](callable: Callable[V],
                             delay: Long,
                             unit: TimeUnit): ScheduledFuture[V] =
        throw new NotImplementedError()

    override def scheduleWithFixedDelay(command: Runnable,
                                        initialDelay: Long, delay: Long,
                                        unit: TimeUnit): ScheduledFuture[_] =
        throw new NotImplementedError()
}
