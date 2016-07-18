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

import com.codahale.metrics.{Gauge, MetricRegistry}
import com.codahale.metrics.MetricRegistry.name

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

trait MetricRegister {

    protected def registry: MetricRegistry

    protected def registerGauge(gauge: Gauge[_], names: String*) = {
        registry.register(name(classOf[StorageGauge], names: _*), gauge)
    }

    protected def meter(names: String*) = {
        registry.meter(name(classOf[StorageMeter], names: _*))
    }

    protected def counter(names: String*) = {
        registry.counter(name(classOf[StorageCounter], names: _*))
    }

    protected def histogram(names: String*) = {
        registry.histogram(name(classOf[StorageHistogram], names: _*))
    }

    protected def timer(names: String*) = {
        registry.timer(name(classOf[StorageTimer], names: _*))
    }

    protected def gauge[T](f: () => T)(implicit ct: ClassTag[T]) =
        new Gauge[T] {
            override def getValue = f()
        }
}
