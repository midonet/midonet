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

import com.codahale.metrics._
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEventType
import org.apache.curator.framework.api.CuratorEventType._
import org.apache.curator.framework.state.ConnectionState.LOST
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}

trait ZoomMetrics {
    def addReadLatency(latencyInNanos: Long): Unit
    def addReadChildrenLatency(latencyInNanos: Long): Unit
    def addWriteLatency(latencyInNanos: Long): Unit
    def addMultiLatency(latencyInNanos: Long): Unit
    def addLatency(eventType: CuratorEventType, latencyInNanos: Long): Unit

    /**
     * The number of triggered watchers for ZooKeeper nodes.
     */
    protected val objectWatcherTriggerCount = new AtomicLong
    /**
     * The number of triggered watchers for ZooKeeper children.
     */
    protected val classWatcherTriggerCount = new AtomicLong

    /**
     * Increment the count of node watcher triggered.
     */
    def incNodeWatcherTriggered(): Unit

    /**
     * Increment the count of children watcher triggered (the on.
     */
    def incChildrenWatchTriggered(): Unit

    /**
     * The count of premature closing of Node observables
     * (when one or more observers are subscribed to the observable).
     */
    protected val nodeObservablePrematureCloseCount = new AtomicLong

    /**
     * The count of timeout exceptions encountered when
     * interacting with ZooKeeper.
     */
    protected val zkNodeExistsExceptionCount = new AtomicLong

    /**
     * The count of NoNode exception encountered when interacting
     * with ZooKeeper.
     */
    protected val zkNoNodeExceptionCount = new AtomicLong

    /**
     * The count of connection losses to ZooKeeper.
     */
    protected val zkConnectionLossCount = new AtomicLong

    /**
     * Increment the count of premature close of observables.
     */
    def incObservablePrematureClose(): Unit

    /**
     * Increment the count of ZooKeeper timeout exception encountered.
     */
    def incZkNodeExistsExceptionCount(): Unit

    /**
     * Increment the count of ZooKeeper NoNode exception encountered.
     */
    def incZkNoNodeExceptionCount(): Unit

    /**
     * A listener for changes in ZooKeeper connection state.
     */
    def zkConnectionStateListener(): ConnectionStateListener
}

object BlackHoleZoomMetrics extends ZoomMetrics {
    override def addMultiLatency(latencyInNanos: Long): Unit = {}
    override def addReadLatency(latencyInNanos: Long): Unit = {}
    override def addReadChildrenLatency(latencyInNanos: Long): Unit = {}
    override def addWriteLatency(latencyInNanos: Long): Unit = {}
    def addLatency(eventType: CuratorEventType, latencyInNanos: Long): Unit = {}
    override def incNodeWatcherTriggered(): Unit = {}
    override def incChildrenWatchTriggered(): Unit = {}
    override def incObservablePrematureClose(): Unit = {}
    override def incZkNoNodeExceptionCount(): Unit = {}
    override def incZkNodeExistsExceptionCount(): Unit = {}
    override def zkConnectionStateListener(): ConnectionStateListener =
        new ConnectionStateListener {
            override def stateChanged(client: CuratorFramework,
                                      newState: ConnectionState): Unit = {}
        }
}

class JmxZoomMetrics(zoom: ZookeeperObjectMapper, registry: MetricRegistry)
    extends ZoomMetrics {

    private var readLatencies: Histogram = _
    private var readChildrenLatencies: Histogram = _
    private var writeLatencies: Histogram = _
    private var multiLatencies: Histogram = _

    registerMetrics()

    private def registerMetrics(): Unit = {
        val metricPrefix = "Zoom-" + zoom.basePath()

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
                    val res = new StringBuilder()
                    for ((clazz, count) <- zoom.objectObservableCountPerClass) {
                        res.append(clazz + ":" + count + "\n")
                    }
                    res.toString
                }
            })
        registry.register(metricPrefix + "TypeObservableList",
            new Gauge[String] {
                @Override
                def getValue(): String = {
                    val classes = zoom.classObservableSet
                    classes.foldLeft(new StringBuilder)((builder, clazz) => {
                        builder.append(clazz.getName + "\n")
                    }).toString
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
        registry.register(metricPrefix + "ObservablePrematureCloseCount",
            new Gauge[Long] {
                @Override
                def getValue(): Long = {
                    nodeObservablePrematureCloseCount.get()
                }
        })
        registry.register(metricPrefix + "ZKNoNodeExceptionCount",
            new Gauge[Long] {
                @Override
                def getValue(): Long = {
                    zkNoNodeExceptionCount.get()
                }
        })
        registry.register(metricPrefix + "ZKNodeExistsExceptionCount",
            new Gauge[Long] {
                @Override
                def getValue(): Long = {
                    zkNodeExistsExceptionCount.get()
                }
        })
        registry.register(metricPrefix + "ZKConnectionLostExceptionCount",
            new Gauge[Long] {
                @Override
                def getValue(): Long = {
                    zkConnectionLossCount.get()
                }
        })

        readLatencies = registry.histogram(metricPrefix + "readMicroSec")
        readChildrenLatencies = registry.histogram(metricPrefix +
                                    "readChildrenMicroSec")
        writeLatencies = registry.histogram(metricPrefix + "writeMicroSec")
        multiLatencies = registry.histogram(metricPrefix + "multiMicroSec")
    }

    override def addReadLatency(latencyInNanos: Long): Unit =
        readLatencies.update(latencyInNanos / 1000l)
    override def addReadChildrenLatency(latencyInNanos: Long): Unit =
        readChildrenLatencies.update(latencyInNanos / 1000l)
    override def addWriteLatency(latencyInNanos: Long): Unit =
        writeLatencies.update(latencyInNanos / 1000l)
    override def addMultiLatency(latencyInNanos: Long): Unit =
        multiLatencies.update(latencyInNanos / 1000l)

    override def addLatency(eventType: CuratorEventType, latencyInNanos: Long)
    : Unit = eventType match {
        case CREATE | DELETE | SET_DATA =>
            addWriteLatency(latencyInNanos)
        case EXISTS | GET_DATA =>
            addReadLatency(latencyInNanos)
        case CHILDREN =>
            addReadChildrenLatency(latencyInNanos)
        case _ =>
    }

    override def incNodeWatcherTriggered(): Unit =
        objectWatcherTriggerCount.incrementAndGet()
    override def incChildrenWatchTriggered(): Unit =
        classWatcherTriggerCount.incrementAndGet()

    override def incObservablePrematureClose(): Unit =
        nodeObservablePrematureCloseCount.incrementAndGet()
    override def incZkNoNodeExceptionCount(): Unit =
        zkNoNodeExceptionCount.incrementAndGet()
    override def incZkNodeExistsExceptionCount(): Unit =
        zkNodeExistsExceptionCount.incrementAndGet()

    override def zkConnectionStateListener(): ConnectionStateListener = {
        new ConnectionStateListener {
            override def stateChanged(client: CuratorFramework,
                                      state: ConnectionState): Unit =
                state match {
                    case LOST => zkConnectionLossCount.incrementAndGet()
                    case _ =>
                }

        }
    }
}
