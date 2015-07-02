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

package org.midonet.cluster.data.storage

import java.util.concurrent.atomic.AtomicLong

import scala.util.Random

import com.codahale.metrics.{Gauge, Histogram, MetricRegistry}

class ZoomMetrics(zoom: ZookeeperObjectMapper, registry: MetricRegistry) {
    /* Two counters that respectively keep track of the number of triggered
       watchers for object and class observables. */
    val objectWatcherTriggerCount = new AtomicLong(0)
    val classWatcherTriggerCount = new AtomicLong(0)

    private var readLatencies: Histogram = _
    private var readChildrenLatencies: Histogram = _
    private var writeLatencies: Histogram = _
    private var multiLatencies: Histogram = _

    registerMetrics()

    private def registerMetrics(): Unit = {
        val zoomId = zoom.basePath() + "-" + Random.nextInt(100).toString
        val metricPrefix = "Zoom-" + zoomId + "-"

        registry.register(metricPrefix + "ZkConnectionState",
            new Gauge[String] {
                @Override
                def getValue(): String = {
                    zoom.zkConnectionState
                }
            })
        registry.register(metricPrefix + "TypeObservableCount", new Gauge[Int] {
            @Override
            def getValue(): Int = {
                zoom.classObservableCount
            }
        })
        registry.register(metricPrefix + "ObjectObservableCount",
            new Gauge[Int] {
                @Override
                def getValue(): Int = {
                    zoom.objectObservableCount
                }
            })
        registry.register(metricPrefix + "ObjectObservableCountPerType",
            new Gauge[String] {
                @Override
                def getValue(): String = {
                    zoom.objectObservableCountPerClass
                }
            })
        registry.register(metricPrefix + "TypeObservableList",
            new Gauge[String] {
                @Override
                def getValue(): String = {
                    zoom.classObservables
                }
            })
        registry.register(metricPrefix + "ObjectWatchersTriggered",
        new Gauge[Long] {
            @Override
            def getValue(): Long = {
                objectWatcherTriggerCount.get()
            }
        })
        registry.register(metricPrefix + "TypeWatchersTriggered",
            new Gauge[Long] {
                @Override
                def getValue(): Long = {
                    classWatcherTriggerCount.get()
            }
        })
        readLatencies = registry.histogram(metricPrefix + "readMilliSec")
        readChildrenLatencies = registry.histogram(metricPrefix +
                                    "readChildrenMilliSec")
        writeLatencies = registry.histogram(metricPrefix + "writeMilliSec")
        multiLatencies = registry.histogram(metricPrefix + "multiMilliSec")
    }

    def addReadLatency(latency: Long): Unit = readLatencies.update(latency)
    def addReadChildrenLatency(latency: Long): Unit =
        readChildrenLatencies.update(latency)
    def addWriteLatency(latency: Long): Unit = writeLatencies.update(latency)
    def addMultiLatency(latency: Long): Unit = multiLatencies.update(latency)
}
