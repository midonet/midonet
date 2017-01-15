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

import scala.concurrent.Await
import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry

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
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.util.TestDatapathState
import org.midonet.midolman.{DatapathState, SimulationBackChannel}
import org.midonet.odp.ports.{GreTunnelPort, VxLanTunnelPort}
import org.midonet.packets.IPv6Subnet
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent._
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class VppControllerIntegrationTest extends FeatureSpec with Matchers
                                           with GivenWhenThen with BeforeAndAfter
                                           with TopologyBuilder
                                           with OvsDatapathHelper
                                           with MidonetEventually {

    private var config: MidolmanConfig = _
    private var backend: MidonetBackend = _
    private var metricRegistry: MetricRegistry = _
    private var dpState: DatapathState = _
    private var vt: VirtualTopology = _
    private var vtpm: VirtualToPhysicalMapper = _
    private val log = Logger(LoggerFactory.getLogger(getClass))
    private val uplinkDevice = "vpp_uplink_dev"
    private val hostId = HostIdGenerator.getHostId

    class VppTestDatapathState extends TestDatapathState {

        override val datapath = createDatapath("test_data_path")

        private val ovs = new VppOvs(datapath, config.fip64)
        private val dpPort = ovs.createDpPort(uplinkDevice)

        override def getDpPortNumberForVport(vportId: UUID): Integer =
            dpPort.getPortNo

        override val tunnelFip64VxLanPort: VxLanTunnelPort =
            ovs.createVxlanDpPort("tnvxlan-fip64", 5321)

        override val tunnelOverlayGrePort: GreTunnelPort =
            ovs.createGreDpPort("tngre-overlay")

        override val tunnelOverlayVxLanPort: VxLanTunnelPort =
            ovs.createVxlanDpPort("tnvxlan-vtep", 4789)

        def cleanup(): Unit = {
            ovs.deleteDpPort(dpPort)
            ovs.deleteDpPort(tunnelFip64VxLanPort)
            ovs.deleteDpPort(tunnelOverlayGrePort)
            ovs.deleteDpPort(tunnelOverlayVxLanPort)
        }

    }

    class TestableVppController(datapathState: DatapathState,
                                vt: VirtualTopology)
        extends VppController(hostId, datapathState,
                              new VppOvs(datapathState.datapath, config.fip64),
                              vt) {

        def ! (message: Any) = send(message)
    }

    before {
        killVpp()
        config = MidolmanConfig.forTests
        backend = new MidonetTestBackend(curatorParam = null)
        metricRegistry = new MetricRegistry
        log info s"Creating test device $uplinkDevice"
        cmd(s"sudo ip link add name $uplinkDevice type dummy", 1)
        cmd(s"sudo ip l set up dev $uplinkDevice")
        dpState = new VppTestDatapathState
        log info "Done"
        vt = createVirtualTopology()
        vtpm = createVirtualToPhysicalMapper()
        backend.startAsync().awaitRunning()
    }

    after {
        log info "Post test clean up"
        backend.stopAsync().awaitTerminated()
        dpState.asInstanceOf[VppTestDatapathState].cleanup()
        deleteDatapath(dpState.datapath, log)
        cmd(s"sudo ip link del $uplinkDevice", 1)
        log info "Done"
        killVpp()
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

    private def createVppContoller(): TestableVppController = {
        new TestableVppController(dpState, vt)
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

    private def killVpp() = new ProcessBuilder("pkill vpp".split("\\s+"):_*)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start().waitFor()

    feature("VPP controller handles uplink ports") {
        scenario("VPP controller launches VPP process") {
            Given("A VPP controller")
            val vpp = createVppContoller()
            vpp.startAsync().awaitRunning()

            And("A router port")
            val routerId = UUID.randomUUID
            val router = createRouter(routerId, None, Some("edge_router"))
            val edgeRouterPortId = UUID.randomUUID
            val port = createRouterPort(id = toProto(edgeRouterPortId),
                                        routerId = Some(toProto(routerId)),
                                        portSubnet = new IPv6Subnet("2001::2", 64))
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Notifying an uplink port")
            Await.result(VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0), 1 minute)

            Then("Veth-pair interfaces are created")
            val portPrefix = edgeRouterPortId.toString.substring(0, 8)
            val ovsEnd = "ovs-" + portPrefix
            val vppEnd = "vpp-" + portPrefix
            eventually {
                cmd(s"ip l show $ovsEnd", wait_timeout = 1) shouldBe 0
                cmd(s"ip l show $vppEnd", wait_timeout = 1) shouldBe 0
            }
            // TODO: We should be able to wait for the next message to complete
            // TODO: execution. However, we cannot do so now due to a bug in the
            // TODO: VPP controller, where the rollback fails.
            Await.result(VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = false, 0), 1 minute)
            eventually {
                Thread.sleep(1)
                cmd(s"ip l show $ovsEnd", wait_timeout = 1) shouldBe 1
                cmd(s"ip l show $vppEnd", wait_timeout = 1) shouldBe 1
            }
            vpp.stopAsync().awaitTerminated()
        }
    }
}
