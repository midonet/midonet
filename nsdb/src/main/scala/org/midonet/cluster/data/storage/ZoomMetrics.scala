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

import scala.reflect.ClassTag

import com.codahale.metrics._
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEventType
import org.apache.curator.framework.api.CuratorEventType._
import org.apache.curator.framework.state.ConnectionState.LOST
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.{NodeExistsException, NoNodeException}

import org.midonet.cluster.util.{NodeObservableClosedException, DirectoryObservableClosedException}

trait ZoomMetrics {

    /** Examines the given Throwable, and updates the appropriate counters if 
      * relevant.
      */
    final def count(t: Throwable): Throwable = {
        t match {
            case e: DirectoryObservableClosedException =>
                observableClosedPrematurely()
            case e: NodeObservableClosedException =>
                observableClosedPrematurely()
            case e: NoNodeException =>
                zkNoNodeTriggered()
            case nee: NodeExistsException =>
                zkNodeExistsTriggered()
            case _ =>
        }
        t
    }

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
    def nodeWatcherTriggered(): Unit

    /**
     * Increment the count of children watcher triggered (the on.
     */
    def childrenWatcherTriggered(): Unit

    /**
     * The count of premature closing of Node observables
     * (when one or more observers are subscribed to the observable).
     */
    protected val nodeObservablePrematureCloseCount = new AtomicLong

    /**
     * The count of timeout exceptions encountered when
     * interacting with ZooKeeper.
     */
    protected val zkNodeExistsCount = new AtomicLong

    /**
     * The count of NoNode exception encountered when interacting
     * with ZooKeeper.
     */
    protected val zkNoNodeCount = new AtomicLong

    /**
     * The count of connection losses to ZooKeeper.
     */
    protected val zkConnectionLossCount = new AtomicLong

    /**
     * Increment the count of premature close of observables.
     */
    def observableClosedPrematurely(): Unit

    /**
     * Increment the count of ZooKeeper timeout exception encountered.
     */
    def zkNodeExistsTriggered(): Unit

    /**
     * Increment the count of ZooKeeper NoNode exception encountered.
     */
    def zkNoNodeTriggered(): Unit

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
    override def nodeWatcherTriggered(): Unit = {}
    override def childrenWatcherTriggered(): Unit = {}
    override def observableClosedPrematurely(): Unit = {}
    override def zkNoNodeTriggered(): Unit = {}
    override def zkNodeExistsTriggered(): Unit = {}
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

    private def gauge[T](f: Unit => T)(implicit ct: ClassTag[T]) =
        new Gauge[T] { override def getValue = f() }

    private def registerMetrics(): Unit = {
        val metricPrefix = "Zoom-" + zoom.basePath()

        registry.register(metricPrefix + "ZkConnectionState",
                          gauge{ _ => zoom.zkConnectionState })
        registry.register(metricPrefix + "TypeObservableCount",
                          gauge { _ => zoom.classObservableCount })
        registry.register(metricPrefix + "ObjectObservableCount",
                          gauge{_ => zoom.objectObservableCount })
        registry.register(metricPrefix + "ObjectObservableCountPerType",
                          gauge { _ =>
                              val res = new StringBuilder()
                              for ((clazz, count) <- zoom
                                  .objectObservableCounters) {
                                  res.append(clazz + ":" + count + "\n")
                              }
                              res.toString()
                          })
        registry.register(metricPrefix + "TypeObservableList",
                          gauge { _ =>
                              val classes = zoom.existingClassObservables
                              classes.foldLeft(
                                  new StringBuilder)((builder, clazz) => {
                                    builder.append(clazz.getName + "\n")
                                  }).toString()
                          })
        registry.register(metricPrefix + "ObjectWatchersTriggered",
                          gauge { _ => objectWatcherTriggerCount.get() })
        registry.register(metricPrefix + "TypeWatchersTriggered",
                          gauge { _ => classWatcherTriggerCount.get() })
        registry.register(metricPrefix + "ObservablePrematureCloseCount",
                          gauge { _ => nodeObservablePrematureCloseCount.get()})
        registry.register(metricPrefix + "ZKNoNodeExceptionCount",
                          gauge { _ => zkNoNodeCount.get() })
        registry.register(metricPrefix + "ZKNodeExistsExceptionCount",
                          gauge { _ => zkNodeExistsCount.get() })
        registry.register(metricPrefix + "ZKConnectionLostExceptionCount",
                          gauge { _ => zkConnectionLossCount.get()})

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
        case CREATE | DELETE | SET_DATA => addWriteLatency(latencyInNanos)
        case EXISTS | GET_DATA => addReadLatency(latencyInNanos)
        case CHILDREN => addReadChildrenLatency(latencyInNanos)
        case _ =>
    }

    override def nodeWatcherTriggered(): Unit =
        objectWatcherTriggerCount.incrementAndGet()
    override def childrenWatcherTriggered(): Unit =
        classWatcherTriggerCount.incrementAndGet()

    override def observableClosedPrematurely(): Unit =
        nodeObservablePrematureCloseCount.incrementAndGet()
    override def zkNoNodeTriggered(): Unit =
        zkNoNodeCount.incrementAndGet()
    override def zkNodeExistsTriggered(): Unit =
        zkNodeExistsCount.incrementAndGet()

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
