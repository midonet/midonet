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

package org.midonet.cluster

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.util.process.ProcessHelper

/**
  * Helper object to abstract how the cluster process is executed by the
  * Midonet Agent. The current implementation basically starts a subprocess
  * with a specific template definition that starts a cluster node. This
  * cluster-compute-minions defines which minions are enabled and its
  * configuration if necessary.
  */
object ClusterProcess {

    private val log = LoggerFactory.getLogger("org.midonet.midolman-cluster")

    val ClusterCommand = "/usr/share/midonet-cluster/midonet-cluster-start"

    val ClusterTemplate = "cluster-compute-minions"

    private var clusterProcess: Process = _

    def start(): Unit = {
        clusterProcess = ProcessHelper
            .newDemonProcess(s"$ClusterCommand -t $ClusterTemplate",
                             log, "org.midonet.cluster.minions")
            .run()
        log info "Starting Cluster node minions on process " +
                 s"${ProcessHelper.getProcessPid(clusterProcess)}"
    }

    def stop(): Unit = {
        if (clusterProcess ne null) {
            clusterProcess.destroy()
        }
        log info "Cluster node stopped"
    }

}
