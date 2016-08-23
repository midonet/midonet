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
package org.midonet.cluster.data.storage.metrics

import scala.compat.Platform.ConcurrentModificationException

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.name

import org.midonet.cluster.data.storage._
import org.midonet.cluster.monitoring.metrics.StorageCounter

class StorageErrorMetrics(registry: MetricRegistry) {

    val concurrentModificationExceptionCounter =
        registry.counter(name(classOf[StorageCounter],
                              "concurrentModificationException"))
    val conflictExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "conflictException"))
    val objectReferencedExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "objectReferencedException"))
    val objectExistsExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "objectExistsException"))
    val objectNotFoundExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "objectNotFoundException"))
    val nodeExistsExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "nodeExistsException"))
    val nodeNotFoundExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "nodeNotFoundException"))

    val objectObservableClosedCounter =
        registry.counter(name(classOf[StorageCounter], "objectObservableClosed"))
    val classObservableClosedCounter =
        registry.counter(name(classOf[StorageCounter], "classObservableClosed"))
    val stateObservableClosedCounter =
        registry.counter(name(classOf[StorageCounter], "stateObservableClosed"))

    val objectObservableErrorCounter =
        registry.counter(name(classOf[StorageCounter], "objectObservableError"))
    val classObservableErrorCounter =
        registry.counter(name(classOf[StorageCounter], "classObservableError"))
    val stateObservableErrorCounter =
        registry.counter(name(classOf[StorageCounter], "stateObservableError"))

    /**
      * Examines the given Throwable, and updates the appropriate counters if
      * relevant.
      */
    final def count(e: Throwable): Unit = {
        e match {
            case _: ConcurrentModificationException =>
                concurrentModificationExceptionCounter.inc()
            case _: ReferenceConflictException =>
                conflictExceptionCounter.inc()
            case _: ObjectReferencedException =>
                objectReferencedExceptionCounter.inc()
            case _: ObjectExistsException =>
                objectExistsExceptionCounter.inc()
            case _: StorageNodeExistsException =>
                nodeExistsExceptionCounter.inc()
            case _: StorageNodeNotFoundException =>
                nodeNotFoundExceptionCounter.inc()
            case _: NotFoundException =>
                objectNotFoundExceptionCounter.inc()
            case _ =>
        }
    }
}
