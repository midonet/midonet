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

import scala.util.Random
import scala.util.control.NonFatal

import com.codahale.metrics.{Gauge, MetricRegistry}

object ZoomMetrics {
    /* The path used to read and write data to Zookeeper and measure latency. */
    private val DUMMY_PATH = "/dummy"

    def registerMetrics(zoom: ZookeeperObjectMapper,
                        registry: MetricRegistry): Unit = {
        val zoomId = zoom.basePath() + "-" + Random.nextInt(100).toString

        registry.register("Zk-ConnectionState-" + zoomId, new Gauge[String] {
            @Override
            def getValue(): String = {
                zoom.zkConnectionState
            }
        })
        registry.register("Zoom-#ClassWatchers-" + zoomId, new Gauge[Int] {
            @Override
            def getValue(): Int = {
                zoom.classWatcherCount
            }
        })
        registry.register("Zoom-#ObjectWatchers-" + zoomId, new Gauge[Int] {
            @Override
            def getValue(): Int = {
                zoom.objWatcherCount
            }
        })
        registry.register("Zoom-ObjectSubscribers-" + zoomId,
            new Gauge[String] {
                @Override
                def getValue(): String = {
                    zoom.classSubscribers
                }
            })
        registry.register("Zoom-ClassSubscribers-" + zoomId, new Gauge[String] {
            @Override
            def getValue(): String = {
                zoom.classSubscribers
            }
        })
        registry.register("Zoom-#ObjectWatchersTriggered-" + zoomId,
        new Gauge[Long] {
            @Override
            def getValue(): Long = {
                ZookeeperObjectMapper.objWatcherTriggerCount.get()
            }
        })
        registry.register("Zoom-#ClassWatchersTriggered-" + zoomId,
            new Gauge[Long] {
                @Override
                def getValue(): Long = {
                    ZookeeperObjectMapper.classWatcherTriggerCount.get()
            }
        })
        registry.register("Zoom-ZkReadLatency-" + zoomId, new Gauge[String] {
            @Override
            def getValue(): String = {
                try {
                    zoom.createDummyPath(DUMMY_PATH)
                    val start = System.currentTimeMillis()
                    zoom.readDummyPath(DUMMY_PATH)
                    val end = System.currentTimeMillis()
                    zoom.deleteDummyPath(DUMMY_PATH)
                    (end - start) + " ms"
                } catch {
                    case NonFatal(e) => "Unable to perform operation"
                }
            }
        })
        registry.register("Zoom-ZkWriteLatency-" + zoomId, new Gauge[String] {
            @Override
            def getValue(): String = {
                try {
                    val start = System.currentTimeMillis()
                    zoom.createDummyPath(DUMMY_PATH)
                    val end = System.currentTimeMillis()
                    zoom.deleteDummyPath(DUMMY_PATH)
                    (end - start) + " ms"
                } catch {
                    case NonFatal(e) => "Unable to perform operation"
                }
            }
        })
    }
}
