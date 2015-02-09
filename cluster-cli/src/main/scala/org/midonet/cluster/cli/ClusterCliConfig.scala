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

package org.midonet.cluster.cli

import org.midonet.config.{ConfigInt, ConfigString, ConfigGroup}

object ClusterCliConfig {
    final val Group = "cluster-cli"
    final val DefaultPrompt = "cluster"
    final val DefaultHosts = "127.0.0.1:2181"
    final val DefaultZoomPath = "/midonet/zoom"
}

/**
 * The configuration of the cluster CLI.
 */
@ConfigGroup(ClusterCliConfig.Group)
trait ClusterCliConfig {

    import ClusterCliConfig._

    /** The Cluster CLI prompt message. */
    @ConfigString(key = "prompt", defaultValue = DefaultPrompt)
    def prompt: String

    /** Comma separated host:port values for each ZooKeeper node. */
    @ConfigString(key = "hosts", defaultValue = DefaultHosts)
    def hosts: String

    /** The ZooKeeper path for the ZooKeeper Object Mapper. */
    @ConfigString(key = "zoom_path", defaultValue = DefaultZoomPath)
    def zoomPath: String

    /** The timeout for a storage operation. */
    @ConfigInt(key = "operation_timeout_millis", defaultValue = 1000)
    def operationTimeoutMillis: Int

}
