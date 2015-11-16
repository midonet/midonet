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

package org.midonet.cluster.storage

import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.WatchedEvent

import org.midonet.cluster.backend.zookeeper.{ZkDirectory, ZkConnection}
import org.midonet.util.eventloop.Reactor

/**
 * Provides an implementation of the legacy [[ZkConnection]] to use with
 * Curator. Because the Curator connection lifecycle is handled separately,
 * the corresponding connection methods are overwritten in this implementation.
 * This allows the creation of a [[ZkDirectory]]
 * based on Curator.
 */
class CuratorZkConnection(curator: CuratorFramework, reactor: Reactor)
    extends ZkConnection {

    override def open(): Unit = { /* Not used */ }
    override def reopen(): Unit = { /* Not used */ }
    override def close(): Unit = { /* Not used */ }
    override def process(event: WatchedEvent): Unit = { /* Not used */ }
    override def getZooKeeper = curator.getZookeeperClient.getZooKeeper

}
