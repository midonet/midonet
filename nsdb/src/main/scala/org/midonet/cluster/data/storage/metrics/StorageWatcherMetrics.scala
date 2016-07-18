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

trait StorageWatcherMetrics {

    def nodeWatcherTriggered(): Unit

    def childrenWatcherTriggered(): Unit
}

object StorageWatcherMetrics {

    val Nil = new StorageWatcherMetrics {

        override def nodeWatcherTriggered(): Unit = {}

        override def childrenWatcherTriggered(): Unit = {}
    }
}

class JmxStorageWatcherMetrics(override val registry: MetricRegistry)
    extends StorageWatcherMetrics with MetricRegister {

    private val nodeTriggeredWatchers = counter("nodeTriggeredWatchers")
    private val childrenTriggeredWatchers = counter("childrenTriggeredWatchers")

    override def nodeWatcherTriggered(): Unit =
        nodeTriggeredWatchers.inc()

    override def childrenWatcherTriggered(): Unit =
        childrenTriggeredWatchers.inc()
}