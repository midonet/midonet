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

import org.scalatest.Suite

import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, ZkConnection, SessionUnawareConnectionWatcher}
import org.midonet.cluster.storage.CuratorZkConnection
import org.midonet.util.eventloop.{Reactor, CallingThreadReactor}

trait MidonetBackendTest extends Suite with CuratorTestFramework {

    protected var reactor: Reactor = _
    protected var connection: ZkConnection = _
    protected var connectionWatcher: ZkConnectionAwareWatcher = _

    override def beforeEach(): Unit = {
        super.beforeEach()
        reactor = new CallingThreadReactor
        connection = new CuratorZkConnection(curator, reactor)
        connectionWatcher = new SessionUnawareConnectionWatcher()
        connectionWatcher.setZkConnection(connection)
    }

}
