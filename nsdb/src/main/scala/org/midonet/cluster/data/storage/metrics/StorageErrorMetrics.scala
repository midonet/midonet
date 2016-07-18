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

import com.codahale.metrics.MetricRegistry

import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import org.midonet.cluster.util.{DirectoryObservableClosedException, NodeObservableClosedException}

trait StorageErrorMetrics {

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

    protected def nodeObservablePrematurelyClosed(): Unit

    protected def nodeExistsTriggered(): Unit

    def noNodeTriggered(): Unit
}

class JmxStorageErrorMetrics(override val registry: MetricRegistry)
    extends StorageErrorMetrics with MetricRegister {

    private val nodeObservablePrematureCloses = counter("nodeObservablePrematureCloses")
    private val noNodesExceptions = counter("noNodesExceptions")
    private val nodeExistsExceptions = counter("nodeExistsExceptions")

    override def nodeObservablePrematurelyClosed(): Unit =
        nodeObservablePrematureCloses.inc()

    override def noNodeTriggered(): Unit =
        noNodesExceptions.inc()

    override def nodeExistsTriggered(): Unit =
        nodeExistsExceptions.inc()
}
