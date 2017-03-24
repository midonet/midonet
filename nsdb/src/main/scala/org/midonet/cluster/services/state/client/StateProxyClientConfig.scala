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

import com.typesafe.config.{Config, ConfigException}

class StateProxyClientConfig(val conf: Config) {

    def enabled = conf.getBoolean("state_proxy.enabled")
    def numNetworkThreads = conf.getInt("state_proxy.network_threads")
    def softReconnectDelay = conf
        .getDuration("state_proxy.soft_reconnect_delay",
                     TimeUnit.MILLISECONDS) millis
    def maxSoftReconnectAttempts = conf
        .getInt("state_proxy.max_soft_reconnect_attempts")
    def hardReconnectDelay = conf
        .getDuration("state_proxy.hard_reconnect_delay",
                     TimeUnit.MILLISECONDS) millis
    def connectTimeout = try {
        conf.getDuration("state_proxy.connect_timeout",
                         TimeUnit.MILLISECONDS) millis
    } catch {
        case _: ConfigException => Connection.DefaultConnectTimeout
    }
}
