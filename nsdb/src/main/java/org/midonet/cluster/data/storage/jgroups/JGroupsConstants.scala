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

package org.midonet.cluster.data.storage.jgroups

trait JGroupsConstants {
    /* Path in ZooKeeper where broker ip:ports are published. */
    private[jgroups] val ZK_SERVER_ROOT = "/jgroups/servers"

    /* Number of times we try to write/read a broker ip:port pair to/from
       ZooKeeper. */
    private[jgroups] val MAX_ZK_ATTEMPTS = 10
}
