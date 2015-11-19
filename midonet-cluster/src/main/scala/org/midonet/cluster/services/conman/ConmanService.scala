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

package org.midonet.cluster.services.conman

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster.conmanLog
import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.{Minion, ClusterService}

/**
  * This is the cluster service for container management across the MidoNet
  * agents. The service monitors the current configuration of service
  * containers and the set of active agent nodes, and schedules the creation
  * or deletion of the containers via the NSDB.
  */
@ClusterService(name = "container-manager")
class ConmanService @Inject()(nodeContext: Context,
                              config: ClusterConfig,
                              metrics: MetricRegistry)
    extends Minion(nodeContext) {

    private val log = Logger(LoggerFactory.getLogger(conmanLog))

    override def isEnabled = config.conman.isEnabled

    override def doStart(): Unit = {
        log info "Starting Container Management service"
        notifyStarted()
    }

    override def doStop(): Unit = {
        notifyStopped()
    }
}
