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
            addWriteLatency(latencyInNanos)
        case EXISTS | GET_DATA =>
            addReadLatency(latencyInNanos)
        case CHILDREN =>
            addReadChildrenLatency(latencyInNanos)
        case _ =>
    }

    def addReadLatency(latencyInNanos: Long): Unit

    def addReadChildrenLatency(latencyInNanos: Long): Unit

    def addWriteLatency(latencyInNanos: Long): Unit

    def addMultiLatency(latencyInNanos: Long): Unit

    def connectionStateListener(): ConnectionStateListener
}

class JmxStoragePerformanceMetrics(override val registry: MetricRegistry)
    extends StoragePerformanceMetrics with MetricRegister {

    private val connectionsLostMeter = meter("connectionsLostMeter")
    private val connectionsCreatedMeter = meter("connectionsCreatedMeter")

    val readTimer = timer("readTimer")
    val readChildrenTimer = timer("readChildrenTimer")
    val writeTimer = timer("writeTimer")
    val multiTimer = timer("multiTimer")

    override def addReadLatency(latencyInNanos: Long): Unit =
        readTimer.update(latencyInNanos, NANOSECONDS)

    override def addReadChildrenLatency(latencyInNanos: Long): Unit =
        readChildrenTimer.update(latencyInNanos, NANOSECONDS)

    override def addWriteLatency(latencyInNanos: Long): Unit =
        writeTimer.update(latencyInNanos, NANOSECONDS)

    override def addMultiLatency(latencyInNanos: Long): Unit =
        multiTimer.update(latencyInNanos, NANOSECONDS)

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
