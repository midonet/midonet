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

import com.codahale.metrics._

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}

import org.midonet.cluster.data.storage.ZookeeperObjectMapper

trait StorageMetrics {

    def error: StorageErrorMetrics
    def performance: StoragePerformanceMetrics
    def session: StorageSessionMetrics
    def watchers: StorageWatcherMetrics

    def connectionStateListeners: Seq[ConnectionStateListener]
}

object StorageMetrics {

    val Nil = new StorageMetrics {

        override val error = StorageErrorMetrics.Nil
        override val performance = StoragePerformanceMetrics.Nil
        override val session = StorageSessionMetrics.Nil
        override val watchers = StorageWatcherMetrics.Nil

        override val connectionStateListeners = Seq.empty
    }

    def nilStateListener() = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit = {}
    }
}

class JmxStorageMetrics(zoom: ZookeeperObjectMapper,
                        override val registry: MetricRegistry)
    extends StorageMetrics with MetricRegister {

    override val error = new JmxStorageErrorMetrics(registry)
    override val performance = new JmxStoragePerformanceMetrics(registry)
    override val session = new JmxStorageSessionMetrics(registry)
    override val watchers = new JmxStorageWatcherMetrics(registry)

    ZoomMetricRegister(zoom, registry).registerAll()

    override def connectionStateListeners() =
        Seq(session.connectionStateListener(),
            performance.connectionStateListener())
}
