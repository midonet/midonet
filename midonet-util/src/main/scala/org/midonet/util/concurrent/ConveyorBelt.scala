/*
 * Copyright 2014 Midokura SARL
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

import java.util.HashMap

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object ConveyorBelt {
    type Cargo = () => Future[_]
    type KeyedCargo[-K] = (K, () => Unit) => Future[_]
}

/**
 * This class in a generalization over an actor. It ensures only one, possibly
 * asynchronous computation is executing at any given time. Note that while a
 * computation is ongoing, new items are accepted and queued.
 * ¡¡¡ The thread completing the computation (i.e., the future) must be the same
 *     as the one submitting the work items. This class is not thread-safe. !!!
 */
class ConveyorBelt(exceptionHandler: Throwable => Unit) {
    import ConveyorBelt._

    var handling = false
    val goods = new mutable.Queue[Cargo]

    def handle(cargo: Cargo): Unit =
        if (handling) {
            goods enqueue cargo
        } else {
            handling = true
            handleCargo(cargo)
        }

    private def handleCargo(cargo: Cargo): Unit =
        cargo().onComplete {
            case Failure(t) =>
                exceptionHandler(t)
                tryHandleCargo()
            case Success(_) =>
                tryHandleCargo()
        }(ExecutionContext.callingThread)

    private def tryHandleCargo(): Unit =
        if (goods.isEmpty) {
            handling = false
        } else {
            handleCargo(goods.dequeue())
        }
}

/**
 * A multi-lane conveyor belt, where each lane is identified by a key of type K.
 * A given computation may signal it is the last computation by calling the
 * provided shutdown callback. Note that a shutdown is only carried out if
 * there are no more scheduled computations and that it can be cancelled if
 * a computation is scheduled after it.
 */
sealed class MultiLaneConveyorBelt[K](exceptionHandler: Throwable => Unit) {
    import ConveyorBelt._

    val lanes = new HashMap[K, ConveyorBelt]()

    def handle(key: K, cargo: KeyedCargo[K]): Unit = {
        var belt = lanes get key
        if (belt eq null) {
            belt = new ConveyorBelt(exceptionHandler)
            lanes put (key, belt)
        }

        val requestShutdown = () => belt handle (() => {
            scheduleShutdown(key)
            Future successful null
        })
        belt handle (() => cargo(key, requestShutdown))
    }

    def containsLane(key: K) = lanes containsKey key

    private def scheduleShutdown(key: K): Unit =
        if (lanes.get(key).goods.size == 0) {
            lanes remove key
        }
}
