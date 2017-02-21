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

package org.midonet

import java.nio.channels.spi.SelectorProvider
import java.util.{Arrays, UUID}
import java.util.concurrent.ThreadLocalRandom

import scala.util.control.NonFatal
import scala.util.control.Exception._

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.{ShardedSimulationBackChannel, FlowTranslator, DatapathState}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath.{PacketExecutor, FlowProcessor}
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.monitoring.metrics.DatapathMetrics
import org.midonet.midolman.monitoring.metrics.PacketExecutorMetrics
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink.{NeighOps, Link, LinkOps}
import org.midonet.odp._
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.{FlowActions, FlowKeys, FlowActionOutput}
import org.midonet.odp.ports.{VxLanTunnelPort, NetDevPort}
import org.midonet.odp.util.TapWrapper
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.util.concurrent.NanoClock
import org.midonet.netlink.exceptions.NetlinkException
import java.nio.ByteBuffer
import rx.Observer

@RunWith(classOf[JUnitRunner])
class ReclaimTest extends FeatureSpec
                 with BeforeAndAfterAll
                 with Matchers {

    feature("Datapath is reclaimed") {
        scenario ("Reclaim or claim") {
            val dpName = "reclaim-test"
            val vethName = "reclaimdp"

            val buf = BytesUtil.instance.allocateDirect(512)
            val channelFactory = new NetlinkChannelFactory()

            val channel: NetlinkChannel = channelFactory.create(
                blocking = true)

            val writer = new NetlinkWriter(channel)
            val reader = new NetlinkReader(channel)
            val families = OvsNetlinkFamilies.discover(channel)
            val protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                           families)
            val datapath = try {
                println("Getting datapath")
                protocol.prepareDatapathGet(0, dpName, buf)
                NetlinkUtil.rpc(buf, writer,
                                reader, Datapath.buildFrom)
            } catch {
                case e: NetlinkException =>
                    println(s"Not found, creating datapath")
                    protocol.prepareDatapathCreate(dpName, buf)
                    NetlinkUtil.rpc(buf, writer,
                                    reader, Datapath.buildFrom)
            }

            try {
                println("Loading data port")
                protocol.prepareDpPortGet(datapath.getIndex, null,
                                          vethName, buf)
                val port = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
                println("Port found, setting")
                protocol.prepareDpPortSet(datapath.getIndex,
                                          port, buf)
                NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
            } catch {
                case e: NetlinkException =>
                    println("Creating data port")
                    protocol.prepareDpPortCreate(
                        datapath.getIndex,
                        new NetDevPort(vethName), buf)
                    NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
            }

            NetlinkUtil.readNetlinkNotifications(
                channel,
                reader, NetlinkMessage.HEADER_SIZE,
                new Observer[ByteBuffer]() {
                    override def onNext(bb: ByteBuffer): Unit = {
                        println(s"Got buffer ${bb.limit}")
                    }
                    override def onError(ex: Throwable): Unit = {
                    }
                    override def onCompleted(): Unit = {}
                })

        }

    }
}
