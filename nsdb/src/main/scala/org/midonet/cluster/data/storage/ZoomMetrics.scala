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
import rx.Observer
import rx.observers.Observers

trait ZoomMetrics {
    val objectWatcherTriggerCount: AtomicLong
    val classWatcherTriggerCount: AtomicLong
    def addReadLatency(latencyInNanos: Long): Unit
    def addReadChildrenLatency(latencyInNanos: Long): Unit
    def addWriteLatency(latencyInNanos: Long): Unit
    def addMultiLatency(latencyInNanos: Long): Unit
    def incrementObjectWatcherTriggeredObs[T](): Observer[T]
    def incrementClassWatchersTriggeredObs[T](): Observer[T]
}

class EmptyZoomMetrics extends ZoomMetrics {
    override val objectWatcherTriggerCount: AtomicLong = new AtomicLong(0)
    override val classWatcherTriggerCount: AtomicLong = new AtomicLong(0)
    override def addMultiLatency(latencyInNanos: Long): Unit = {}
    override def addReadLatency(latencyInNanos: Long): Unit = {}
    override def addReadChildrenLatency(latencyInNanos: Long): Unit = {}
    override def addWriteLatency(latencyInNanos: Long): Unit = {}
    override def incrementObjectWatcherTriggeredObs[T](): Observer[T] =
        Observers.empty()
    override def incrementClassWatchersTriggeredObs[T](): Observer[T] =
        Observers.empty()
}

class JmxZoomMetrics(zoom: ZookeeperObjectMapper, registry: MetricRegistry)
    extends ZoomMetrics {

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
        readLatencies = registry.histogram(metricPrefix + "readMicroSec")
        readChildrenLatencies = registry.histogram(metricPrefix +
                                    "readChildrenMicroSec")
        writeLatencies = registry.histogram(metricPrefix + "writeMicroSec")
        multiLatencies = registry.histogram(metricPrefix + "multiMicroSec")
    }

    /* Two counters that respectively keep track of the number of triggered
       watchers for object and class observables. */
    override val objectWatcherTriggerCount = new AtomicLong(0)
    override val classWatcherTriggerCount = new AtomicLong(0)

    override def addReadLatency(latencyInNanos: Long): Unit =
        readLatencies.update(latencyInNanos / 1000l)
    override def addReadChildrenLatency(latencyInNanos: Long): Unit =
        readChildrenLatencies.update(latencyInNanos / 1000l)
    override def addWriteLatency(latencyInNanos: Long): Unit =
        writeLatencies.update(latencyInNanos / 1000l)
    override def addMultiLatency(latencyInNanos: Long): Unit =
        multiLatencies.update(latencyInNanos / 1000l)

    override def incrementObjectWatcherTriggeredObs[T](): Observer[T] =
        new Observer[T] {
            override def onCompleted(): Unit =
                objectWatcherTriggerCount.incrementAndGet()
            override def onError(e: Throwable): Unit = {}
            override def onNext(t: T): Unit =
                objectWatcherTriggerCount.incrementAndGet()
        }
    override def incrementClassWatchersTriggeredObs[T](): Observer[T] =
        new Observer[T] {
            override def onCompleted(): Unit =
                classWatcherTriggerCount.incrementAndGet()
            override def onError(e: Throwable): Unit = {}
            override def onNext(t: T): Unit =
                objectWatcherTriggerCount.incrementAndGet()
        }
}
