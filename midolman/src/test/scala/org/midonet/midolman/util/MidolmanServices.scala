/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.util

import java.util.{ArrayList, UUID}

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import com.codahale.metrics.{MetricFilter, MetricRegistry}
import com.google.inject.Injector

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.PortStateStorage._
import org.midonet.midolman.FlowController
import org.midonet.midolman.ShardedSimulationBackChannel
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.flows.FlowExpirationIndexer.Expiration
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.state.PeerResolver
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.mock.{MockDatapathChannel, MockFlowProcessor, MockUpcallDatapathConnectionManager}
import org.midonet.netlink.{MockNetlinkChannel, NetlinkChannelFactory}
import org.midonet.odp.FlowMatch
import org.midonet.odp.protos.{MockOvsDatapathConnection, OvsDatapathConnection}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.MockClock
import org.midonet.util.functors.Callback0

trait MidolmanServices {
    var injector: Injector

    var clock = new MockClock

    def config =
        injector.getInstance(classOf[MidolmanConfig])

    def peerResolver =
        injector.getInstance(classOf[PeerResolver])

    def virtualTopology =
        injector.getInstance(classOf[VirtualTopology])

    def virtConfBuilderImpl =
        injector.getInstance(classOf[VirtualConfigurationBuilders])

    def setPortActive(portId: UUID, hostId: UUID, active: Boolean): Unit = {
        injector.getInstance(classOf[MidonetBackend]).stateStore
                .setPortActive(portId, hostId, active, 0L)
                .toBlocking.first()
    }

    def metricRegistry = injector.getInstance(classOf[MetricRegistry])

    lazy val metrics = {
        val metricsReg = metricRegistry
        metricsReg.removeMatching(MetricFilter.ALL)
        new PacketPipelineMetrics(metricsReg, 1)
    }

    implicit val hostId: UUID =
        UUID.randomUUID()

    def mockDpConn()(implicit ec: ExecutionContext, as: ActorSystem) = {
        dpConn().asInstanceOf[MockOvsDatapathConnection]
    }

    def mockDpChannel = {
        injector.getInstance(classOf[DatapathChannel])
                .asInstanceOf[MockDatapathChannel]
    }

    def mockNetlinkChannelFactory =
        injector.getInstance(classOf[NetlinkChannelFactory])

    def mockNetlinkChannel =
        mockNetlinkChannelFactory.create().asInstanceOf[MockNetlinkChannel]

    def flowProcessor = {
        injector.getInstance(classOf[FlowProcessor])
                .asInstanceOf[MockFlowProcessor]
    }

    lazy val simBackChannel = injector.getInstance(
        classOf[ShardedSimulationBackChannel]).registerProcessor

    val mockFlowInvalidation = new FlowController() {
        var tags = List[FlowTag]()

        def getAndClear() = {
            val ret = tags
            tags = List[FlowTag]()
            ret
        }

        override def addFlow(fmatch: FlowMatch, flowTags: ArrayList[FlowTag],
                             removeCallbacks: ArrayList[Callback0],
                             expiration: Expiration): ManagedFlow = null
        override def addRecircFlow(fmatch: FlowMatch,
                                   recircMatch: FlowMatch,
                                   flowTags: ArrayList[FlowTag],
                                   removeCallbacks: ArrayList[Callback0],
                                   expiration: Expiration): ManagedFlow = null
        override def removeDuplicateFlow(mark: Int): Unit = {}
        override def flowExists(mark: Int): Boolean = false
        override def shouldProcess = false
        override def process(): Unit = {}
        override def invalidateFlowsFor(tag: FlowTag) = tags = tags :+ tag
        override def recordPacket(packetLen: Int,
                                  tags: ArrayList[FlowTag]): Unit = {}
    }

    def dpConn()(implicit ec: ExecutionContext, as: ActorSystem):
        OvsDatapathConnection = {
        val mockConnManager =
            injector.getInstance(classOf[UpcallDatapathConnectionManager]).
                asInstanceOf[MockUpcallDatapathConnectionManager]
        mockConnManager.initialize()
        mockConnManager.conn.getConnection
    }
}
