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

package org.midonet.cluster.util

import org.apache.zookeeper.{KeeperException, WatchedEvent}
import org.slf4j.LoggerFactory

import org.midonet.cluster.backend.zookeeper.ZkConnection
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}

object TestZkTools {
    def instantZkConnWatcher = new ZookeeperConnectionWatcher {
        val log = LoggerFactory.getLogger(this.getClass)
        override def setZkConnection(conn: ZkConnection): Unit = ???
        override def scheduleOnDisconnect(runnable: Runnable): Unit = ???
        override def scheduleOnReconnect(runnable: Runnable): Unit = ???
        override def handleError(objectDesc: String, retry: Runnable,
                                 e: KeeperException): Unit = retry.run()
        override def handleError(objectDesc: String, retry: Runnable,
                                 e: StateAccessException): Unit = retry.run()
        override def handleDisconnect(runnable: Runnable): Unit = runnable.run()
        override def handleTimeout(runnable: Runnable): Unit = runnable.run()
        override def process(event: WatchedEvent): Unit = {
            log.info(s"Ignoring $event")
        }
    }
}
