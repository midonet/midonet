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
    def bufferSize = conf.getInt("zookeeper.buffer_size")
}

class CassandraConfig(val conf: Config) {
    def servers = conf.getString("cassandra.servers")
    def cluster = conf.getString("cassandra.cluster")
    def replication_factor = conf.getInt("cassandra.replication_factor")
}

/**
 * This file defines configuration parameters for MidoNet's state tables
 * (e.g., MAC and ARP tables) which rely on Kafka. These state tables
 * are called Merged Maps.
 */
class KafkaConfig(val conf: Config) {
    /* True iff merged maps are enabled (merged maps replace replicated maps). */
    def useMergedMaps = conf.getBoolean("kafka.use_merged_maps")
    /* List of Kafka brokers: host1:port1, host2:port2, host3:port3 */
    def brokers = conf.getString("kafka.brokers")
    /* Zookeeper connect string: host1:port1, host2: port2, host3: port3. */
    def zkHosts = conf.getString("kafka.zk_hosts")
    /* The number of replicas per topic. */
    def replicationFactor = conf.getInt("kafka.replication_factor")
    /* The connection timeout used by Kafka to connect to ZooKeeper. */
    def zkConnectionTimeout = conf.getInt("kafka.zk_connection_timeout")
    /* The session timeout used for ZooKeeper */
    def zkSessionTimeout = conf.getInt("kafka.zk_session_timeout")
}
