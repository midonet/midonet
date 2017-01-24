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

import com.codahale.metrics._
import com.codahale.metrics.MetricRegistry.name

import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.monitoring.metrics.StorageGauge

class StorageMetrics(registry: MetricRegistry) {

    val error = new StorageErrorMetrics(registry)
    val performance = new StoragePerformanceMetrics(registry)
    val session = new StorageSessionMetrics(registry)
    val watchers = new StorageWatcherMetrics(registry)

    val connectionStateListeners = Seq(session.connectionStateListener(),
                                       performance.connectionStateListener())

    def build(zoom: ZookeeperObjectMapper): Unit = {

        registry.register(name(classOf[StorageGauge], "connectionState"), gauge {
            zoom.connectionState
        })
        registry.register(name(classOf[StorageGauge], "failFastConnectionState"), gauge {
            zoom.failFastConnectionState
        })
        registry.register(name(classOf[StorageGauge], "objectObservables"), gauge {
            zoom.objectObservableCount
        })
        registry.register(name(classOf[StorageGauge], "classObservables"), gauge {
            zoom.classObservableCount
        })
        for (clazz <- zoom.objectClasses.keys) {
            registry.register(name(classOf[StorageGauge], "objectObservables",
                                   clazz.getSimpleName), gauge {
                zoom.objectObservableCount(clazz)
            })
        }
    }

    private def gauge[T](f: => T): Gauge[T] = {
        new Gauge[T] { override def getValue = f }
    }
}
