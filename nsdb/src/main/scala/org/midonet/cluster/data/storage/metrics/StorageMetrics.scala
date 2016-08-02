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

import scala.reflect.ClassTag

import com.codahale.metrics._
import com.codahale.metrics.MetricRegistry.name

import org.midonet.cluster.data.storage.ZookeeperObjectMapper

/**
  * Class names to publish metrics. They are meant to be used while creating
  * a metrics object from the Metrics library, and will act as a marker to
  * organize the metrics when exported via JMX.
  */
trait StorageCounter
trait StorageMeter
trait StorageGauge
trait StorageHistogram
trait StorageTimer

class StorageMetrics(zoom: ZookeeperObjectMapper, registry: MetricRegistry) {

    val error = new StorageErrorMetrics(registry)
    val performance = new StoragePerformanceMetrics(registry)
    val session = new StorageSessionMetrics(registry)
    val watchers = new StorageWatcherMetrics(registry)

    def connectionStateListeners() = Seq(session.connectionStateListener(),
                                         performance.connectionStateListener())

    registry.register(name(classOf[StorageGauge], "zkConnectionState"), gauge {
        zoom.zkConnectionState _
    })

    registry.register(name(classOf[StorageGauge], "typeObservableCount"), gauge {
        zoom.startedClassObservableCount _
    })

    registry.register(name(classOf[StorageGauge], "objectObservableCount"), gauge {
        zoom.startedObjectObservableCount _
    })

    registry.register(name(classOf[StorageGauge], "objectObservableCountPerType"),
                      gauge { () =>
        val res = new StringBuilder()
        for ((clazz, count) <- zoom.objectObservableCounters) {
            res.append(clazz + ":" + count + "\n")
        }
        res.toString()
    })

    registry.register(name(classOf[StorageGauge], "typeObservableList"),
                      gauge { () =>
        val classes = zoom.startedClassObservables
        classes.foldLeft(new StringBuilder)((builder, clazz) => {
            builder.append(clazz.getName + "\n")
        }).toString()
    })

    private def gauge[T](f: () => T)(implicit ct: ClassTag[T]) =
        new Gauge[T] {
            override def getValue = f()
        }
}
