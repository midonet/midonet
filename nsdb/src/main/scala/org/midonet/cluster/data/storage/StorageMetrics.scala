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

import scala.reflect.ClassTag

import com.codahale.metrics._
import com.codahale.metrics.MetricRegistry.name

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEventType
import org.apache.curator.framework.api.CuratorEventType._
import org.apache.curator.framework.state.ConnectionState._
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import org.midonet.cluster.util.{DirectoryObservableClosedException, NodeObservableClosedException}

/**
  * Class names to publish metrics. They are meant to be used while creating
  * a metrics object from the Metrics library, and will act as a marker to
  * organize the metrics when exported via JMX.
  */

trait StorageCounter
trait StorageMeter
trait StorageGauge
trait StorageHistogram

trait StorageMetrics {

    /** Examines the given Throwable, and updates the appropriate counters if
      * relevant.
      */
    final def count(t: Throwable): Throwable = {
        t match {
            case _: DirectoryObservableClosedException =>
                nodeObservablePrematurelyClosed()
            case _: NodeObservableClosedException =>
                nodeObservablePrematurelyClosed()
            case _: NoNodeException =>
                noNodeTriggered()
            case _: NodeExistsException =>
                nodeExistsTriggered()
            case _ =>
        }
        t
    }

    final def addLatency(eventType: CuratorEventType, latencyInNanos: Long)
    : Unit = eventType match {
        case CREATE | DELETE | SET_DATA =>
            addWriteEvent()
            addWriteLatency(latencyInNanos / 1000L)
        case EXISTS | GET_DATA =>
            addReadEvent()
            addReadLatency(latencyInNanos / 1000L)
        case CHILDREN =>
            addReadChildrenEvent()
            addReadChildrenLatency(latencyInNanos / 1000L)
        case _ =>
    }

    def addReadEvent(): Unit

    def addReadChildrenEvent(): Unit

    def addWriteEvent(): Unit

    def addReadLatency(latencyInNanos: Long): Unit

    def addReadChildrenLatency(latencyInNanos: Long): Unit

    def addWriteLatency(latencyInNanos: Long): Unit

    def addMultiLatency(latencyInNanos: Long): Unit

    /**
      * Increment the count of node watcher triggered.
      */
    def nodeWatcherTriggered(): Unit

    /**
      * Increment the count of children watcher triggered.
      */
    def childrenWatcherTriggered(): Unit

    /**
      * Increment the count of premature close of observables.
      */
    protected def nodeObservablePrematurelyClosed(): Unit

    /**
      * Increment the count of ZooKeeper timeout exceptions encountered.
      */
    protected def nodeExistsTriggered(): Unit

    /**
      * Increment the count of ZooKeeper NoNode exceptions encountered.
      */
    def noNodeTriggered(): Unit

    /**
      * A listener for changes in ZooKeeper connection state.
      */
    def connectionStateListener(): ConnectionStateListener

}

object StorageMetrics {

    val Nil = new StorageMetrics {
        override def addReadEvent(): Unit = {}

        override def addReadChildrenEvent(): Unit = {}

        override def addWriteEvent(): Unit = {}

        override def addReadLatency(latencyInNanos: Long): Unit = {}

        override def addReadChildrenLatency(latencyInNanos: Long): Unit = {}

        override def addWriteLatency(latencyInNanos: Long): Unit = {}

        override def addMultiLatency(latencyInNanos: Long): Unit = {}

        override def nodeWatcherTriggered(): Unit = {}

        override def childrenWatcherTriggered(): Unit = {}

        override def nodeObservablePrematurelyClosed(): Unit = {}

        override def nodeExistsTriggered(): Unit = {}

        override def noNodeTriggered(): Unit = {}

        override def connectionStateListener(): ConnectionStateListener =
            new ConnectionStateListener {
                override def stateChanged(client: CuratorFramework,
                                          newState: ConnectionState): Unit = {}
            }
    }
}

class JmxStorageMetrics(zoom: ZookeeperObjectMapper, registry: MetricRegistry)
    extends StorageMetrics {

    override def addReadEvent(): Unit =
        readsMeter.mark()

    override def addReadChildrenEvent(): Unit =
        childrenReadsMeter.mark()

    override def addWriteEvent(): Unit =
        writesMeter.mark()

    override def addReadLatency(latencyInNanos: Long): Unit =
        readLatencies.update(latencyInNanos / 1000L)

    override def addReadChildrenLatency(latencyInNanos: Long): Unit =
        readChildrenLatencies.update(latencyInNanos / 1000L)

    override def addWriteLatency(latencyInNanos: Long): Unit =
        writeLatencies.update(latencyInNanos / 1000L)

    override def addMultiLatency(latencyInNanos: Long): Unit =
        multiLatencies.update(latencyInNanos / 1000L)

    override def nodeWatcherTriggered(): Unit =
        nodeTriggeredWatchers.inc()

    override def childrenWatcherTriggered(): Unit =
        childrenTriggeredWatchers.inc()

    override def nodeObservablePrematurelyClosed(): Unit =
        nodeObservablePrematureCloses.inc()

    override def noNodeTriggered(): Unit =
        noNodesExceptions.inc()

    override def nodeExistsTriggered(): Unit =
        nodeExistsExceptions.inc()

    override def connectionStateListener(): ConnectionStateListener = {
        new ConnectionStateListener {
            override def stateChanged(client: CuratorFramework,
                                      state: ConnectionState): Unit =
                state match {
                    case CONNECTED => connectionsCreatedMeter.mark()
                    case LOST => connectionsLostMeter.mark()
                    case _ =>
                }
        }
    }

    private val connectionsLostMeter = meter("connectionsLostMeter")
    private val connectionsCreatedMeter = meter("connectionsCreatedMeter")

    val nodeTriggeredWatchers = counter("nodeTriggeredWatchers")
    val childrenTriggeredWatchers = counter("childrenTriggeredWatchers")
    val nodeObservablePrematureCloses = counter("nodeObservablePrematureCloses")
    val nodeExistsExceptions = counter("nodeExistsExceptions")
    val noNodesExceptions = counter("noNodesExceptions")

    val readsMeter = meter("readsMeter")
    val childrenReadsMeter = meter("childrenReadsMeter")
    val writesMeter = meter("writesMeter")

    val readLatencies = histogram("readLatencies")
    val readChildrenLatencies = histogram("readChildrenLatencies")
    val writeLatencies = histogram("writeLatencies")
    val multiLatencies = histogram("multiLatencies")

    val zkConnectionState = registerGauge(gauge {
            zoom.zkConnectionState _
        }, "zkConnectionState")
    val typeObservableCount = registerGauge(gauge {
            zoom.startedClassObservableCount _
        }, "typeObservableCount")
    val objectObservableCount = registerGauge(gauge {
            zoom.startedObjectObservableCount _
        }, "objectObservableCount")
    val objectObservableCountPerType = registerGauge(gauge { () =>
            val res = new StringBuilder()
            for ((clazz, count) <- zoom.objectObservableCounters) {
                res.append(clazz + ":" + count + "\n")
            }
            res.toString()
        }, "objectObservableCountPerType")
    val typeObservableList = registerGauge(gauge { () =>
            val classes = zoom.startedClassObservables
            classes.foldLeft(
                new StringBuilder)((builder, clazz) => {
                builder.append(clazz.getName + "\n")
            }).toString()
        }, "typeObservableList")

    private def gauge[T](f: () => T)(implicit ct: ClassTag[T]) =
        new Gauge[T] {
            override def getValue = f()
        }

    private def registerGauge(gauge: Gauge[_], names: String*) = {
        registry.register(name(classOf[StorageGauge], names: _*), gauge)
    }

    private def meter(names: String*) = {
        registry.meter(name(classOf[StorageMeter], names: _*))
    }

    private def counter(names: String*) = {
        registry.counter(name(classOf[StorageCounter], names: _*))
    }

    private def histogram(names: String*) = {
        registry.histogram(name(classOf[StorageHistogram], names: _*))
    }
}
