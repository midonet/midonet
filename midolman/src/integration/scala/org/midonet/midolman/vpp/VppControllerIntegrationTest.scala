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

import java.lang.{Process, ProcessBuilder}
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import com.google.common.io.Files
import com.google.inject.Guice
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.junit.Assert
import org.scalatest.junit.JUnitRunner
import org.scalatest.FeatureSpec
import org.slf4j.LoggerFactory

import org.midonet.ErrorCode
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{MidonetBackendTestModule, MidonetTestBackend}
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.{DatapathController, DatapathState, DatapathStateDriver}
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.services.HostIdProvider
import org.midonet.midolman.state.FlowStateStorageFactory
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.util.{MidolmanSpec, MockNetlinkChannelFactory}
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockUpcallDatapathConnectionManager}
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows.{FlowActionOutput, FlowActions}
import org.midonet.odp.ports.{NetDevPort, VxLanTunnelPort}
import org.midonet.odp.ports.VxLanTunnelPort._
import org.midonet.odp.{Datapath, DpPort, OvsNetlinkFamilies, OvsProtocol}
import org.midonet.packets._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VppControllerIntegrationTest extends /*VppIntegrationTest with */MidolmanSpec {

    var testableDpc: DatapathController = _
    val ipv6 = IPv6Addr("2001::3")
    var clusterBridge: UUID = null
    var connManager: MockUpcallDatapathConnectionManager = null
    var interfaceScanner: MockInterfaceScanner = null
    val midolmanConfig = MidolmanConfig.forTests

    private final val rootPath = "/midonet/test"
    private var zkServer: TestingServer = _
    var backEnd: MidonetBackend = _

    registerActors(
        DatapathController -> (() => new DatapathController(
            new DatapathStateDriver(mockDpConn().futures.datapathsCreate("midonet").get()),
            injector.getInstance(classOf[HostIdProvider]),
            injector.getInstance(classOf[InterfaceScanner]),
            injector.getInstance(classOf[MidolmanConfig]),
            injector.getInstance(classOf[UpcallDatapathConnectionManager]),
            simBackChannel,
            clock,
            injector.getInstance(classOf[FlowStateStorageFactory]),
            new MockNetlinkChannelFactory)),
        VppController -> (() => new VppController(
            midolmanConfig,
            new MockUpcallDatapathConnectionManager(
                midolmanConfig),
            new TestDatapathState,
            backEnd))
    )

    override val log = LoggerFactory.getLogger(
        classOf[VppControllerIntegrationTest])

    override def beforeTest() {
        testableDpc = DatapathController.as[DatapathController]
        testableDpc should not be null

        connManager =
            injector.getInstance(classOf[UpcallDatapathConnectionManager]).
                asInstanceOf[MockUpcallDatapathConnectionManager]
        connManager should not be null

        interfaceScanner = injector.getInstance(classOf[InterfaceScanner]).
            asInstanceOf[MockInterfaceScanner]
        interfaceScanner should not be null

        zkServer = new TestingServer
        zkServer.start()

        val config = ConfigFactory.parseString(
            s"""
               |zookeeper.zookeeper_hosts : "${zkServer.getConnectString}"
               |zookeeper.buffer_size : 524288
               |zookeeper.base_retry : 1s
               |zookeeper.max_retries : 10
               |zookeeper.root_key : "$rootPath"
        """.stripMargin)

        val
        injector_curator = Guice.createInjector(new MidonetBackendTestModule(
            config))
        val  curator = injector_curator.getInstance(classOf[CuratorFramework])
        backEnd = new  MidonetTestBackend(curator)
        backEnd.startAsync().awaitRunning()
    }


    private def addInterface(name: String, mtu: Int, ipAddr: IPAddr) {
        val intf = new InterfaceDescription(name, 1)
        if (ipAddr != null)
            intf.setInetAddress(ipAddr.toString)
        intf.setMtu(mtu)
        intf.setUp(true)
        intf.setHasLink(true)
        interfaceScanner.addInterface(intf)
    }

    feature("VPP controller v6->v4 ping") {

        scenario("ping from external ipv6 to fip6") {

            //val myHostId = UUID.randomUUID()
            val ifName = "test_bgp0"
            val obs = new TestAwaitableObserver[LocalPortActive]
            VirtualToPhysicalMapper.portsActive.subscribe(obs)

            val providerRouterId = newRouter("edge_router")
            val portId = newRouterPort(providerRouterId, new MAC(0x060504030201L),
                                     "2001::3", "2001::", 64,
                                     vni = 0, None, None)
            addHostVrnPortMapping(hostId, portId, ifName)
            And("its network interface with ipv6 port becomes available")
            addInterface(ifName, 9000, null)

            Then("the DpC should create the datapath port")
            val portNumber = testableDpc.driver
                .getDpPortNumberForVport(portId)
            portNumber should not equal null
            obs.getOnNextEvents.size() shouldBe 1
            obs.getOnNextEvents.get(0) shouldBe
            LocalPortActive(portId, portNumber,
                            active = true)
        }
    }
}