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

package org.midonet.cluster.services.vxgw

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.midolman.state.ZookeeperConnectionWatcher

/** An implementation of the VxLAN Gateway Service that supports high
  * availability on the hardware VTEPs. The service allows making bindings
  * from ports/vlan pairs in different hardware VTEPs forming a single Logical
  * Switch, and synces Mac-Port pairs across both MidoNet and all the VTEPs that
  * participate in the Logical Switch.
  *
  * Instances of the service coordinate in an active-passive configuration using
  * Zookeeper. Only one node will be elected as leader and perform all VxLAN
  * Gateway management. When a node loses leadership (voluntarily, or because
  * of a failure, partition, etc.) a different instance will be elected and
  * take over the management.
  */
@ClusterService(name = "vxgw")
class VxlanGatewayService @Inject()(nodeCtx: ClusterNode.Context,
                                    dataClient: DataClient,
                                    zkConnWatcher: ZookeeperConnectionWatcher,
                                    curator: CuratorFramework,
                                    metrics: MetricRegistry,
                                    conf: ClusterConfig)
    extends Minion(nodeCtx) {

    private val log = LoggerFactory.getLogger(vxgwLog)

    // TODO: take these out to a service metrics container
    private val networkCount = metrics.counter(s"${conf.vxgw.Prefix}.networks")
    private val vxgwCount = metrics.counter(s"${conf.vxgw.Prefix}.vxgws")
    def numNetworks: Long = networkCount.getCount
    def numVxGWs: Long = vxgwCount.getCount

    override def isEnabled = conf.vxgw.isEnabled

    override def doStart(): Unit = {
        log.info("THIS SERVICE IS TEMPORARILY DISABLED DURING MIGRATION TO")
        notifyStarted()
    }

    override def doStop(): Unit = {
        try {
            shutdown()
            notifyStopped()
        } catch {
            case t: Throwable =>
                log.error("Failed to shutdown executor", t)
                Thread.currentThread().interrupt()
                notifyFailed(t)
        }
    }

    private def shutdown(): Unit = {
    }

}

