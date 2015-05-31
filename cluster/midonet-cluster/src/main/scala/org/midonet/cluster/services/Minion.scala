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

package org.midonet.cluster.services

import com.google.common.util.concurrent.AbstractService

import org.midonet.cluster.ClusterNode.Context

/** Define a sub-service that runs as part of the Midonet Cluster. This
  * should expose the necessary API to let the Daemon babysit its minions.
  *
  * @param nodeContext metadata about the node where this Minion is running
  */
abstract class Minion(nodeContext: Context) extends AbstractService {

    /** Whether the service is enabled on this Cluster node. */
    def isEnabled: Boolean
}
