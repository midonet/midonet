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

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

/**
 * This file defines configuration parameters required to bootstrap a connection
 * to the MidoNet backend services, such as ZooKeeper, etc.
 */
class MidonetBackendConfig(val conf: Config) {
    def hosts = conf.getString("zookeeper.zookeeper_hosts")
    def sessionTimeout = conf.getDuration("zookeeper.session_timeout", TimeUnit.MILLISECONDS).toInt
    def graceTime = conf.getDuration("zookeeper.session_gracetime", TimeUnit.MILLISECONDS).toInt
    def rootKey = conf.getString("zookeeper.root_key")
    def curatorEnabled = conf.getBoolean("zookeeper.curator_enabled")
    def maxRetries = conf.getInt("zookeeper.max_retries")
    def retryMs = conf.getDuration("zookeeper.base_retry", TimeUnit.MILLISECONDS)
    def useNewStack = conf.getBoolean("zookeeper.use_new_stack")
    def dataDir = conf.getString("zookeeper.data_dir")
    def logDir = conf.getString("zookeeper.log_dir")
    def allowEmbed = conf.getBoolean("zookeeper.allow_embed")
}

class CassandraConfig(val conf: Config) {
    def servers = conf.getString("cassandra.servers")
    def cluster = conf.getString("cassandra.cluster")
    def replication_factor = conf.getInt("cassandra.replication_factor")
}
