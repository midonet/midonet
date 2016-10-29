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

package org.midonet.midolman.vpp

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.gracefulStop
import akka.testkit.TestActorRef

import com.codahale.metrics.MetricRegistry
import com.typesafe.scalalogging.Logger

import org.junit.Assert
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.CreateOp
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.{DatapathState, SimulationBackChannel}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager
import org.midonet.odp.Datapath
import org.midonet.odp.ports.{NetDevPort, VxLanTunnelPort}
import org.midonet.packets.{IPv6Addr, IPv6Subnet}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.midolman.util.TestDatapathState

@RunWith(classOf[JUnitRunner])
class VppControllerIntegrationTest extends FeatureSpec with Matchers
                                           with GivenWhenThen with BeforeAndAfter
                                           with TopologyBuilder {

    private implicit var as: ActorSystem = _
    private var config: MidolmanConfig = _
    private var backend: MidonetBackend = _
    private var metricRegistry: MetricRegistry = _
    private var dpConnManager: UpcallDatapathConnectionManager = _
    private var dpState: DatapathState = _
    private var vt: VirtualTopology = _
    private var vtpm: VirtualToPhysicalMapper = _
    private var log = Logger(LoggerFactory.getLogger(classOf[VppControllerIntegrationTest]))
    private val uplink_device = "vpp_uplink_dev"

    class VppTestDatapathState extends TestDatapathState {

        override val datapath = OvsDataPath.createDatapath("test_data_path")

        private val ovs = new VppOvs(datapath)
        private val dpPort = ovs.createDpPort(uplink_device)

        override def getDpPortNumberForVport(vportId: UUID): Integer = dpPort.getPortNo
    }

    before {
        as = ActorSystem.create()
        config = MidolmanConfig.forTests
        backend = new MidonetTestBackend(curatorParam = null)
        metricRegistry = new MetricRegistry
        dpConnManager = new MockUpcallDatapathConnectionManager(config)
        log info s"Creating test device $uplink_device"
        cmd(s"sudo ip link add name $uplink_device type dummy", 1)
        cmd(s"sudo ip l set up dev $uplink_device")
        dpState = new VppTestDatapathState
        log info "Done"
        vt = createVirtualTopology()
        vtpm = createVirtualToPhysicalMapper()
        backend.startAsync().awaitRunning()
    }

    after {
        log info "Post test clean up"
        as.shutdown()
        backend.stopAsync().awaitTerminated()
        OvsDataPath.deleteDatapath(dpState.datapath, log)
        cmd(s"sudo ip link del $uplink_device", 1)
        log info "Done"
    }

    private def createVirtualTopology(): VirtualTopology = {
        val executor = new SameThreadButAfterExecutorService
        val backChannel = Mockito.mock(classOf[SimulationBackChannel])
        new VirtualTopology(backend, config, backChannel, null, metricRegistry,
                            executor, executor, () => true)
    }

    private def createVirtualToPhysicalMapper(): VirtualToPhysicalMapper = {
        HostIdGenerator.useTemporaryHostId()
        new VirtualToPhysicalMapper(backend, vt,
                                    new Reflections("org.midonet.midolman"),
                                    HostIdGenerator.getHostId)
    }

    private def createVppContoller(): (Actor, ActorRef) = {
        val ref = TestActorRef[VppController](
            Props(new VppController(dpConnManager, dpState, vt)).
                withDispatcher("akka.test.calling-thread-dispatcher"),
                "TestVppController")
        (ref.underlyingActor, ref)
    }

    def cmd(line: String, wait_timeout: Int = 24 * 3600): Int = {
        val proc = new ProcessBuilder(line.split("\\s+"):_*).start()
        val ok = proc.waitFor(wait_timeout, TimeUnit.SECONDS)
        if (ok) {
            proc.waitFor()
        } else {
            1
        }
    }

    def assertCmd(line: String, wait_timeout: Int): Unit = {
        Assert.assertEquals(s"cmd($line) failed", 0, cmd(line, wait_timeout))
    }

    feature("VPP controller handles uplink ports") {
        scenario("VPP controller launches VPP process") {
            Given("A VPP controller")
            val (vpp, ref) = createVppContoller()

            And("A router port")
            val routerId = UUID.randomUUID
            val router = createRouter(routerId, None, Some("edge_router"))
            val edgeRouterPortId = UUID.randomUUID
            val port = createRouterPort(id = toProto(edgeRouterPortId),
                                        routerId = Some(toProto(routerId)),
                                        portAddress = IPv6Addr("2001::2"),
                                        portSubnet = new IPv6Subnet("2001::", 64))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Notifying an uplink port")
            ref ! LocalPortActive(port.getId, 0, true)
            // Provoke thread switch, so vpp could execute all the commands
            Thread.sleep(1000)
            Then("Vpp process is started and veth-pair interfaces are created")

            val portPrefix = edgeRouterPortId.toString.substring(0, 8)
            val ovsEnd = "ovs-" + portPrefix
            val vppEnd = "vpp-" + portPrefix
            assertCmd(s"ifconfig $ovsEnd", wait_timeout = 1)
            assertCmd(s"ifconfig $vppEnd", wait_timeout = 1)
            ref ! LocalPortActive(port.getId, 0, false)
            Thread.sleep(1000)
        }
    }
}
