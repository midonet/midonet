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

import java.util.concurrent.TimeUnit.NANOSECONDS

import com.codahale.metrics.MetricRegistry

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEventType
import org.apache.curator.framework.api.CuratorEventType._
import org.apache.curator.framework.state.ConnectionState._
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}

trait StoragePerformanceMetrics {

    final def addLatency(eventType: CuratorEventType, latencyInNanos: Long)
    : Unit = eventType match {
        case CREATE | DELETE | SET_DATA =>
            addWriteEvent()
            addWriteLatency(NANOSECONDS.toMillis(latencyInNanos))
        case EXISTS | GET_DATA =>
            addReadEvent()
            addReadLatency(NANOSECONDS.toMillis(latencyInNanos))
        case CHILDREN =>
            addReadChildrenEvent()
            addReadChildrenLatency(NANOSECONDS.toMillis(latencyInNanos))
        case _ =>
    }

    def addReadEvent(): Unit

    def addReadChildrenEvent(): Unit

    def addWriteEvent(): Unit

    def addReadLatency(latencyInNanos: Long): Unit

    def addReadChildrenLatency(latencyInNanos: Long): Unit

    def addWriteLatency(latencyInNanos: Long): Unit

    def addMultiLatency(latencyInNanos: Long): Unit

    def connectionStateListener(): ConnectionStateListener
}

object StoragePerformanceMetrics {

    val Nil = new StoragePerformanceMetrics {

        override def addReadEvent(): Unit = {}

        override def addReadChildrenEvent(): Unit = {}

        override def addWriteEvent(): Unit = {}

        override def addReadLatency(latencyInNanos: Long): Unit = {}

        override def addReadChildrenLatency(latencyInNanos: Long): Unit = {}

        override def addWriteLatency(latencyInNanos: Long): Unit = {}

        override def addMultiLatency(latencyInNanos: Long): Unit = {}

        def connectionStateListener(): ConnectionStateListener =
            StorageMetrics.nilStateListener()
    }
}

class JmxStoragePerformanceMetrics(override val registry: MetricRegistry)
    extends StoragePerformanceMetrics with MetricRegister {

    private val connectionsLostMeter = meter("connectionsLostMeter")
    private val connectionsCreatedMeter = meter("connectionsCreatedMeter")

    private val readsMeter = meter("readsMeter")
    private val childrenReadsMeter = meter("childrenReadsMeter")
    private val writesMeter = meter("writesMeter")

    private val readLatencies = histogram("readLatencies")
    private val readChildrenLatencies = histogram("readChildrenLatencies")
    private val writeLatencies = histogram("writeLatencies")
    private val multiLatencies = histogram("multiLatencies")

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
}
