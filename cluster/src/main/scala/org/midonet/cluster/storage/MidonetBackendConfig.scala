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

import org.midonet.config.{ConfigBool, ConfigGroup, ConfigInt, ConfigString}

/**
 * This file defines configuration parameters required to bootstrap a connection
 * to the MidoNet backend services, such as ZooKeeper, etc.
 */
@ConfigGroup("midonet-backend")
trait MidonetBackendConfig {

    @ConfigString(key = "zookeeper_root_path", defaultValue = "/midonet")
    def zookeeperRootPath: String

    @ConfigString(key = "zookeeper_hosts", defaultValue = "")
    def zookeeperHosts: String

    @ConfigInt(key = "zookeeper_base_retry_ms", defaultValue = 1000)
    def zookeeperRetryMs: Int

    @ConfigInt(key = "zookeeper_max_retries", defaultValue = 10)
    def zookeeperMaxRetries: Int

    /* This property is transitional while we support the dual storage stack.
     * if set, it will tell the Cluster components to use the new storage
     * stack.  It is not documented for production use.  When the new
     * storage layer is deployed, this property should disappear. */
    @ConfigBool(key = "enabled", defaultValue = false)
    def isEnabled: Boolean
}
