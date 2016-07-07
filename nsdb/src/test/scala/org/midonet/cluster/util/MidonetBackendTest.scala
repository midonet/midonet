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

import rx.Observable

import org.midonet.cluster.backend.zookeeper.{SessionUnawareConnectionWatcher, ZkConnection, ZkConnectionAwareWatcher}
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => StateClientConnectionState}
import org.midonet.cluster.services.state.client.{StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.storage.CuratorZkConnection
import org.midonet.util.eventloop.{CallingThreadReactor, Reactor}

trait MidonetBackendTest extends Suite with CuratorTestFramework {

    protected var reactor: Reactor = _
    protected var connection: ZkConnection = _
    protected var connectionWatcher: ZkConnectionAwareWatcher = _
    protected val stateTables = new StateTableClient {
        override def stop(): Boolean = false
        override def observable(table: StateSubscriptionKey): Observable[Notify.Update] =
            Observable.never()
        override def connection: Observable[StateClientConnectionState] =
            Observable.never()
        override def start(): Unit = { }
    }

    override def beforeEach(): Unit = {
        super.beforeEach()
        reactor = new CallingThreadReactor
        connection = new CuratorZkConnection(curator, reactor)
        connectionWatcher = new SessionUnawareConnectionWatcher()
        connectionWatcher.setZkConnection(connection)
    }

}
