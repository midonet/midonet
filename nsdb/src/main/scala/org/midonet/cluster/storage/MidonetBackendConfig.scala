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

import org.midonet.conf.MidoNodeConfigurator

/**
 * This file defines configuration parameters required to bootstrap a connection
 * to the MidoNet backend services, such as ZooKeeper, etc.
 */
class MidonetBackendConfig(val conf: Config) {
    def hosts = conf.getString("zookeeper.zookeeper_hosts")
    def sessionTimeout = conf.getDuration("zookeeper.session_timeout", TimeUnit.MILLISECONDS).toInt
    def graceTime = conf.getDuration("zookeeper.session_gracetime", TimeUnit.MILLISECONDS).toInt
    def rootKey = MidoNodeConfigurator.zkRootKey(conf)
    def maxRetries = conf.getInt("zookeeper.max_retries")
    def retryMs = conf.getDuration("zookeeper.base_retry", TimeUnit.MILLISECONDS)
    def bufferSize = conf.getInt("zookeeper.buffer_size")
}

class CassandraConfig(val conf: Config) {
    def servers = conf.getString("cassandra.servers")
    def cluster = conf.getString("cassandra.cluster")
    def replication_factor = conf.getInt("cassandra.replication_factor")
}

class JGroupsConfig(val conf: Config) {
    def zkBrokersRoot = conf.getString("jgroups.zk_brokers_root")
    def clusterName = conf.getString("jgroups.cluster_name")
    def heartbeatPeriod = conf.getDuration("jgroups.heartbeat_period", TimeUnit.MILLISECONDS)
    def heartbeatTimeout = conf.getDuration("jgroups.heartbeat_timeout", TimeUnit.MILLISECONDS)
}