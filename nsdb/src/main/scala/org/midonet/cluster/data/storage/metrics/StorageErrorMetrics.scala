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

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.name

import org.midonet.cluster.data.storage.StorageException

class StorageErrorMetrics(registry: MetricRegistry) {

    val conflictExceptionCounter =
        registry.counter(name(classOf[StorageException], "conflictException"))
    val concurrentModificationExceptionCounter =
        registry.counter(name(classOf[StorageException],
                              "concurrentModificationException"))
    val objectExistsExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "objectExistsException"))
    val objectNotFoundExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "objectNotFoundException"))
    val nodeExistsExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "nodeExistsException"))
    val nodeNotFoundExceptionCounter =
        registry.counter(name(classOf[StorageCounter], "nodeNotFoundException"))
    val internalObjectMapperExceptionCounter =
        registry.counter(name(classOf[StorageCounter],
                              "internalObjectMapperException"))

    val nodeObservableClosedExceptionCounter =
        registry.counter(name(classOf[StorageCounter],
                              "nodeObservableClosedException"))
    val childrenObservableClosedExceptionCounter =
        registry.counter(name(classOf[StorageCounter],
                              "childrenObservableClosedException"))
    val directoryObservableClosedExceptionCounter =
        registry.counter(name(classOf[StorageCounter],
                              "directoryObservableClosedException"))

    val objectObservableErrorCounter =
        registry.counter(name(classOf[StorageCounter], "objectObservableError"))
    val classObservableErrorCounter =
        registry.counter(name(classOf[StorageCounter], "classObservableError"))

}
