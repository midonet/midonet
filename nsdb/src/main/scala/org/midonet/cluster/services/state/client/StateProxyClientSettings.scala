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

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import com.typesafe.config.Config

class StateProxyClientSettings(val enabled: Boolean,
                               val numNetworkThreads: Int,
                               val reconnectTimeout: FiniteDuration)

object StateProxyClientSettings {

    def from(conf: Config): StateProxyClientSettings = {
        new StateProxyClientSettings(
            enabled=conf.getBoolean("state_proxy.enabled"),
            numNetworkThreads=conf.getInt("state_proxy.network_threads"),
            reconnectTimeout=conf.getDuration("state_proxy.connection_retry_interval",
                                              TimeUnit.MILLISECONDS) millis
        )
    }
}
