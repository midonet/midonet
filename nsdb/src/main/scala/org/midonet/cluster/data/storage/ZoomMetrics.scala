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
import org.apache.curator.framework.state.ConnectionState.{CONNECTED, LOST}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import org.midonet.cluster.util.{DirectoryObservableClosedException, NodeObservableClosedException}

/**
  * Class names to publish metrics. They are meant to be used while creating
  * a metrics object from the Metrics library, and will act as a marker to
  * organize the metrics when exported via JMX.
  */

trait ZoomCounter
trait ZoomMeter
trait ZoomGauge
trait ZoomHistogram

trait ZoomMetrics {

    /** Examines the given Throwable, and updates the appropriate counters if
      * relevant.
      */
    final def count(t: Throwable): Throwable = {
        t match {
            case _: DirectoryObservableClosedException =>
                observableClosedPrematurely()
            case _: NodeObservableClosedException =>
                observableClosedPrematurely()
            case _: NoNodeException =>
                zkNoNodeTriggered()
            case _: NodeExistsException =>
                zkNodeExistsTriggered()
            case _ =>
        }
        t
    }

    final def addLatency(eventType: CuratorEventType, latencyInNanos: Long)
    : Unit = eventType match {
        case CREATE | DELETE | SET_DATA =>
            addWriteLatency(latencyInNanos / 1000L)
        case EXISTS | GET_DATA =>
            addReadLatency(latencyInNanos / 1000L)
        case CHILDREN =>
            addReadChildrenLatency(latencyInNanos / 1000L)
        case _ =>
    }

    def addReadLatency(latencyInNanos: Long): Unit

    def addReadChildrenLatency(latencyInNanos: Long): Unit

    def addWriteLatency(latencyInNanos: Long): Unit

    def addMultiLatency(latencyInNanos: Long): Unit

    /**
      * Increment the count of node watcher triggered.
      */
    def nodeWatcherTriggered(): Unit

    /**
      * Increment the count of children watcher triggered (the on.
      */
    def childrenWatcherTriggered(): Unit

    /**
      * Increment the count of premature close of observables.
      */
    protected def observableClosedPrematurely(): Unit

    /**
      * Increment the count of ZooKeeper timeout exception encountered.
      */
    protected def zkNodeExistsTriggered(): Unit

    /**
      * Increment the count of ZooKeeper NoNode exception encountered.
      */
    def zkNoNodeTriggered(): Unit

    /**
      * A listener for changes in ZooKeeper connection state.
      */
    def zkConnectionStateListener(): ConnectionStateListener

}

object ZoomMetrics {

    val Nil = new ZoomMetrics {
        override def addReadLatency(latencyInNanos: Long): Unit = {}

        override def addReadChildrenLatency(latencyInNanos: Long): Unit = {}

        override def addWriteLatency(latencyInNanos: Long): Unit = {}

        override def addMultiLatency(latencyInNanos: Long): Unit = {}

        override def nodeWatcherTriggered(): Unit = {}

        override def childrenWatcherTriggered(): Unit = {}

        override def observableClosedPrematurely(): Unit = {}

        override def zkNodeExistsTriggered(): Unit = {}

        override def zkNoNodeTriggered(): Unit = {}

        override def zkConnectionStateListener(): ConnectionStateListener =
            new ConnectionStateListener {
                override def stateChanged(client: CuratorFramework,
                                          newState: ConnectionState): Unit = {}
            }
    }
}

class JmxZoomMetrics(zoom: ZookeeperObjectMapper, registry: MetricRegistry)
    extends ZoomMetrics {

    val Tag: String = "Zoom"

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

    override def observableClosedPrematurely(): Unit =
        nodeObservablePrematureCloses.inc()

    override def zkNoNodeTriggered(): Unit =
        noNodesExceptions.inc()

    override def zkNodeExistsTriggered(): Unit =
        nodeExistsExceptions.inc()

    override def zkConnectionStateListener(): ConnectionStateListener = {
        new ConnectionStateListener {
            override def stateChanged(client: CuratorFramework,
                                      state: ConnectionState): Unit =
                state match {
                    case LOST => connectionsLostMeter.mark()
                    case CONNECTED => connectionsCreatedMeter.mark()
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

    val readLatencies = histogram("readLatencies")
    val readChildrenLatencies = histogram("readChildrenLatencies")
    val writeLatencies = histogram("writeLatencies")
    val multiLatencies = histogram("multiLatencies")

    val connectionsLost = registerGauge(gauge {
            connectionsLostMeter.getCount
        }, "connectionsLost")
    val connectionsCreated = registerGauge(gauge {
            connectionsCreatedMeter.getCount
        }, "connectionsCreated")
    val connectionsLostPerMinute = registerGauge(gauge {
            connectionsLostMeter.getOneMinuteRate
        }, "connectionsLostPerMinute")
    val connectionsCreatedPerMinute = registerGauge(gauge {
            connectionsCreatedMeter.getOneMinuteRate
        }, "connectionsCreatedPerMinute")
    val connectionsLostPerFiveMinutes = registerGauge(gauge {
            connectionsLostMeter.getFiveMinuteRate
        }, "connectionsLostPerFiveMinutes")
    val connectionsCreatedPerFiveMinutes = registerGauge(gauge {
            connectionsCreatedMeter.getFiveMinuteRate
        }, "connectionsCreatedPerFiveMinutes")
    val connectionsLostPerFifteenMinutes = registerGauge(gauge {
            connectionsLostMeter.getFifteenMinuteRate
        }, "connectionsLostPerFifteenMinutes")
    val connectionsCreatedPerFifteenMinutes = registerGauge(gauge {
            connectionsCreatedMeter.getFifteenMinuteRate
        }, "connectionsCreatedPerFifteenMinutes")

    val zkConnectionState = registerGauge(gauge {
            zoom.zkConnectionState _
        }, "ZkConnectionState")
    val typeObservableCount = registerGauge(gauge {
            zoom.startedClassObservableCount _
        }, "TypeObservableCount")
    val objectObservableCount = registerGauge(gauge {
            zoom.startedObjectObservableCount _
        }, "ObjectObservableCount")
    val objectObservableCountPerType = registerGauge(gauge { () =>
            val res = new StringBuilder()
            for ((clazz, count) <- zoom.objectObservableCounters) {
                res.append(clazz + ":" + count + "\n")
            }
            res.toString()
        }, "ObjectObservableCountPerType")
    val typeObservableList = registerGauge(gauge { () =>
            val classes = zoom.startedClassObservables
            classes.foldLeft(
                new StringBuilder)((builder, clazz) => {
                builder.append(clazz.getName + "\n")
            }).toString()
        }, "TypeObservableList")

    private def gauge[T](f: () => T)(implicit ct: ClassTag[T]) =
        new Gauge[T] {
            override def getValue = f()
        }

    private def registerGauge(gauge: Gauge[_], names: String*) = {
        registry.register(name(classOf[ZoomGauge], Seq(Tag) ++ names: _*), gauge)
    }

    private def meter(names: String*) = {
        registry.meter(name(classOf[ZoomMeter], Seq(Tag) ++ names: _*))
    }

    private def counter(names: String*) = {
        registry.counter(name(classOf[ZoomCounter], Seq(Tag) ++ names: _*))
    }

    private def histogram(names: String*) = {
        registry.histogram(name(classOf[ZoomHistogram], Seq(Tag) ++ names: _*))
    }
}
