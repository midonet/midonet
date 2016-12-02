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

package org.midonet.midolman.vpp

import java.util.concurrent.ExecutorService

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.google.common.util.concurrent.{AbstractService, Service}

import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.util.concurrent.{ConveyorBelt, Executors}
import org.midonet.util.functors._
import org.midonet.util.logging.Logging

object VppExecutor {

    type Receive = PartialFunction[Any, Future[Any]]

    object Nothing extends Receive {
        def isDefinedAt(x: Any): Boolean = false
        def apply(x: Any): Future[Any] =
            throw new UnsupportedOperationException("Not supported")
    }

}

abstract class VppExecutor extends AbstractService with Logging {

    protected val executor = newExecutor
    protected val ec = ExecutionContext.fromExecutor(executor)

    private val belt = new ConveyorBelt(t => {
        log.error("Error on conveyor belt", t)
    })

    protected def newExecutor: ExecutorService = {
        Executors.singleThreadScheduledExecutor(
            "vpp-controller", isDaemon = true, Executors.CallerRunsPolicy)
    }

    /**
      * Handles messages on the service executor. An overriding class must
      * implement a partial function handling the received message, and return
      * a future that completes when the handling the message has finished.
      */
    protected def receive: Receive

    /**
      * Sends a message to execute on the VPP executor thread. The message
      * must be handled by the [[receive]] method, which will return a
      * future that completes when the handling of the message has finished.
      *
      * The handling of messages is serialized such that a new message will not
      * be processed until the future returned by a previous one has completed.
      */
    protected def send(message: Any): Future[Any] = {
        val currentState = state()
        if (currentState != Service.State.STARTING &&
            currentState != Service.State.RUNNING) {
            return Future.failed(new IllegalStateException("Service not started"))
        }
        val promise = Promise[Any]
        executor.execute(makeRunnable {
            if (receive.isDefinedAt(message)) {
                belt.handle(() => {
                    val future =
                        try receive.apply(message)
                        catch { case NonFatal(e) => Future.failed(e) }
                    promise tryCompleteWith future
                    future
                })
            } else {
                val error = s"Unhandled message $message"
                log warn error
                promise.tryFailure(new UnsupportedOperationException(error))
            }
        })
        promise.future
    }

    override def doStop(): Unit = {
        Executors.shutdown(executor) { _ =>
            log warn s"Exception while stopping VPP controller executor"
        }
    }

}
