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

import java.util.UUID

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.LegacyStorage
import org.midonet.midolman.SimulationBackChannel

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import com.codahale.metrics.{MetricFilter, MetricRegistry}

import com.google.inject.Injector
import org.midonet.netlink.{MockNetlinkChannel, NetlinkChannelFactory}
import org.midonet.sdn.flows.FlowTagger.FlowTag

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.midonet.cluster.data.dhcp.{Host,Subnet}

import org.midonet.cluster.state.PortStateStorage._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.{FlowProcessor, DatapathChannel}
import org.midonet.midolman.flows.FlowTagIndexer
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.simulation.DhcpConfig
import org.midonet.midolman.util.mock.{MockFlowProcessor, MockDatapathChannel, MockUpcallDatapathConnectionManager}
import org.midonet.odp.protos.{OvsDatapathConnection, MockOvsDatapathConnection}
import org.midonet.util.concurrent.MockClock

trait MidolmanServices {
    var injector: Injector

    var clock = new MockClock

    def config =
        injector.getInstance(classOf[MidolmanConfig])

    def virtConfBuilderImpl =
        injector.getInstance(classOf[VirtualConfigurationBuilders])

    def useNewStorageStack: Boolean = System.getProperty("midonet.newStack") != null

    def setPortActive(portId: UUID, hostId: UUID, active: Boolean): Unit = {
        if (useNewStorageStack) {
            injector.getInstance(classOf[MidonetBackend]).stateStore
                    .setPortActive(portId, hostId, active)
                    .toBlocking.first()
        } else {
            injector.getInstance(classOf[LegacyStorage])
                    .setPortActive(portId, hostId, active)
                    .toBlocking.first()
        }
    }

    def useMergedMaps: Boolean = System.getProperty("kafka.use_merged_maps") != null

    def mockDhcpConfig = new DhcpConfig() {
        override def bridgeDhcpSubnets(deviceId: UUID): Seq[Subnet] = List()
        override def dhcpHost(deviceId: UUID, subnet: Subnet, srcMac: String): Option[Host] = None
    }

    def metrics = {
        val metricsReg = injector.getInstance(classOf[MetricRegistry])
        metricsReg.removeMatching(MetricFilter.ALL)
        new PacketPipelineMetrics(metricsReg, 1)
    }

    implicit def hostId: UUID =
        injector.getInstance(classOf[HostIdProviderService]).hostId

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

    def simBackChannel = injector.getInstance(classOf[SimulationBackChannel])

    def flowInvalidator = simBackChannel

    val mockFlowInvalidation = new FlowTagIndexer() {
        val log = Logger(NOPLogger.NOP_LOGGER)
        var tags = List[FlowTag]()

        def getAndClear() = {
            val ret = tags
            tags = List[FlowTag]()
            ret
        }

        override def invalidateFlowsFor(tag: FlowTag) = tags = tags :+ tag
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
