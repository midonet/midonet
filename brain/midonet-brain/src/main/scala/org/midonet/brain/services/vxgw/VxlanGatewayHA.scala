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

package org.midonet.brain.services.vxgw

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterNode
import org.midonet.cluster.DataClient
import org.midonet.midolman.state.ZookeeperConnectionWatcher

class VxlanGatewayHA @Inject() (nodeCtx: ClusterNode.Context,
                                dataClient: DataClient,
                                zkConnWatcher: ZookeeperConnectionWatcher,
                                metrics: MetricRegistry)
    extends VxLanGatewayServiceBase(nodeCtx) {

    private val log = LoggerFactory.getLogger(vxgwLog)

    private var summoner: VxlanGatewaySummoner = _

    override def doStart(): Unit = {
        log.info("Starting service")
        summoner = new VxlanGatewaySummoner(dataClient, zkConnWatcher)
        notifyStarted()
    }

    override def doStop(): Unit = {
        log.info("Stopped service")
        summoner.stop()
        notifyStopped()
    }

}
