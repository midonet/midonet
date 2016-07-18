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
import com.codahale.metrics.MetricRegistry.name

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState._
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}

class StorageSessionMetrics(registry: MetricRegistry) {

    private val timeConnectedHistogram =
        registry.histogram(name(classOf[StorageHistogram],
                                "timeConnectedHistogram"))
    private val timeDisconnectedHistogram =
        registry.histogram(name(classOf[StorageHistogram],
                                "timeDisconnectedHistogram"))

    private var lastTimestamp: Long = _

    def connectionStateListener(): ConnectionStateListener = {
        new ConnectionStateListener {
            override def stateChanged(client: CuratorFramework,
                                      newState: ConnectionState): Unit =
                newState match {
                    case CONNECTED =>
                        lastTimestamp = System.nanoTime()
                    case RECONNECTED =>
                        val disconnected = spentInCurrentState()
                        timeDisconnectedHistogram.update(disconnected)
                    case LOST | SUSPENDED =>
                        val connected = spentInCurrentState()
                        timeConnectedHistogram.update(connected)
                    case _ =>
                }
        }
    }

    private def spentInCurrentState() = {
        val now = System.nanoTime()
        val spentInCurrentState = NANOSECONDS.toMillis(now - lastTimestamp)
        lastTimestamp = now
        spentInCurrentState
    }
}
