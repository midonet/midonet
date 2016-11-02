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

package org.midonet.cluster.services.state.client

import rx.Observable

import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.ConnectionState

trait StateTableClient {

    import StateTableClient.ConnectionState._

    def start(): Unit

    def stop(): Boolean

    def observable(table: StateSubscriptionKey): Observable[Update]

    def connection: Observable[ConnectionState]
}

object StateTableClient {

    object DisabledStateTableClient extends StateTableClient {

        override def start(): Unit = { }

        override def stop(): Boolean = true

        override def observable(table: StateSubscriptionKey): Observable[Update] = {
            Observable.empty()
        }

        override def connection: Observable[ConnectionState] = {
            Observable.just(ConnectionState.Disconnected)
        }
    }

    object ConnectionState extends Enumeration {
        class ConnectionState(val isConnected: Boolean) extends Val
        final val Connected = new ConnectionState(isConnected = true)
        final val Disconnected = new ConnectionState(isConnected = false)
    }
}