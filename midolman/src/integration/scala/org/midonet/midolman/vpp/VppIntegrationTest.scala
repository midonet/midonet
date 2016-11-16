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

import java.nio.ByteBuffer
import java.util.{ArrayList, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.Guice
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.test.TestingServer
import org.junit.Assert
import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.ErrorCode
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{MidonetBackendTestModule, MidonetTestBackend}
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.conf.HostIdGenerator
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKeyEtherType, FlowKeys}
import org.midonet.packets._

@RunWith(classOf[JUnitRunner])
class VppIntegrationTest extends FeatureSpec with TopologyBuilder {
    private final val rootPath = "/midonet/test"
    private var zkServer: TestingServer = _
    private var backend: MidonetBackend = _
    private val log = Logger(LoggerFactory.getLogger(classOf[VppIntegrationTest]))

    def setupStorage(): Unit = {
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
        injector = Guice.createInjector(new MidonetBackendTestModule(
            config))
        val  curator = injector.getInstance(classOf[CuratorFramework])
        backend = new  MidonetTestBackend(curator)
        backend.startAsync().awaitRunning()
    }

    def startVpp(): Process = {
        log.info("Start VPP")
        new ProcessBuilder("/usr/bin/vpp", "unix { nodaemon }").start()
    }

    def cmd(line: String): Int = {
        log.info(s"Executing : $line")
        new ProcessBuilder(line.split("\\s+"):_*)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start().waitFor()
    }

    def assertCmd(line: String): Unit = {
        Assert.assertEquals(s"cmd($line) failed", 0, cmd(line))
    }

    def cmdInNs(name: String, line: String): Int = {
        cmd(s"ip netns exec $name $line")
    }

    def assertCmdInNs(name: String, line: String): Unit = {
        Assert.assertEquals(s"cmd($line) in namespace($name) failed", 0,
                            cmdInNs(name, line))
    }

    def assertVppCtl(line: String): Unit = {
        assertCmd(s"vppctl $line")
    }

    def createNamespace(name: String): Unit = {
        log.info(s"Creating namespace $name")
        assertCmd(s"ip netns add ${name}")
        assertCmd(s"ip l add ${name}dp type veth"
                      + s" peer name ${name}ns")
        assertCmd(s"ip l set netns ${name} dev ${name}ns")
        assertCmd(s"ip l set up dev ${name}dp")
        assertCmdInNs(name, s"ip l set up dev lo")
        assertCmdInNs(name, s"ip l set up dev ${name}ns")
        assertCmdInNs(name, s"sysctl net.ipv6.conf.all.forwarding=1")
        assertCmdInNs(name, s"sysctl net.ipv4.conf.all.forwarding=1")
    }

    def deleteNamespace(name: String): Unit = {
        log.info(s"Deleting namespace $name")
        cmd(s"ip netns del ${name}")
        cmd(s"ip l del dev ${name}dp")
    }

    def createVethPair(name: String): Unit = {
        assertCmd(s"ip l add ${name}-left type veth"
                      + s" peer name ${name}-right")
        assertCmd(s"ip l set up dev ${name}-right")
        assertCmd(s"ip l set up dev ${name}-left")
    }

    def deleteVethPair(name: String): Unit = {
        log.info(s"Deleting veth pair $name")
        cmd(s"ip l del dev ${name}-left")
    }

    private def doDatapathOp(opBuf: (OvsProtocol) => ByteBuffer): Unit = {
        val factory = new NetlinkChannelFactory()
        val famchannel = factory.create(blocking = true,
                                        NetlinkProtocol.NETLINK_GENERIC,
                                        NetlinkUtil.NO_NOTIFICATION)
        val families = OvsNetlinkFamilies.discover(famchannel)
        famchannel.close()

        val channel = factory.create(blocking = false)
        val writer = new NetlinkBlockingWriter(channel)
        val reader = new NetlinkTimeoutReader(channel, 1 minute)
        val protocol = new OvsProtocol(channel.getLocalAddress.getPid, families)

        try {
            val buf = opBuf(protocol)
            writer.write(buf)
            buf.clear()
            reader.read(buf)
            buf.flip()
        } finally {
            channel.close()
        }
    }

    def createDatapath(name: String): Datapath =  {
        val buf = BytesUtil.instance.allocate(2 * 1024)
        try {
            doDatapathOp((protocol) => {
                             protocol.prepareDatapathDel(0, name, buf)
                             buf
                         })
        } catch {
            case t: NetlinkException
                    if (t.getErrorCodeEnum == ErrorCode.ENODEV ||
                            t.getErrorCodeEnum == ErrorCode.ENOENT ||
                            t.getErrorCodeEnum == ErrorCode.ENXIO) =>
        }
        buf.clear()
        doDatapathOp((protocol) => {
                         protocol.prepareDatapathCreate(name, buf)
                         buf
                     })
        buf.position(NetlinkMessage.GENL_HEADER_SIZE)
        Datapath.buildFrom(buf)
    }

    def deleteDatapath(datapath: Datapath): Unit = {
        val buf = BytesUtil.instance.allocate(2 * 1024)
        try {
            doDatapathOp((protocol) => {
                             protocol.prepareDatapathDel(
                                 0, datapath.getName, buf)
                             buf
                         })
        } catch {
            case t: Throwable => log.warn("Error deleting datapath $name", t)
        }
    }

    feature("VPP Api") {
        // this is failing, we need to make this not fail
        ignore("Api connects within 5 seconds when vpp comes up") {
            val connectedLatch = new CountDownLatch(1)
            val apiThread = new Thread() {
                override def run() = {
                    log.info("Connecting to vpp")
                    try {
                        val vppApi = new VppApi("midonet")
                        log.info("Connected to vpp")
                        connectedLatch.countDown()
                    } catch {
                        case t: Throwable => log.error("Error connecting", t)
                    }
                }
            };
            apiThread.start()
            log.info("Sleep for a second so that connect has initiated")
            Thread.sleep(1000)

            val proc = startVpp()
            try {
                Assert.assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
            } finally {
                proc.destroy()
                apiThread.join()
            }
        }

        // this is failing, we need to make this not fail
        ignore("API call responds if vpp crashes") {
            val ns = "foo"
            val proc = startVpp()
            Thread.sleep(1000) // only needed while first testcase is failing
            val api = new VppApi("test")
            try {
                createNamespace(ns)
                log.info("Connecting api")

                log.info("Killing vpp")
                proc.destroy()
                log.info("Making api call")
                Await.result(api.createDevice("${ns}dp", None),
                             1 minute)
            } finally {
                api.close()
                proc.destroy()
                deleteNamespace(ns)
            }
        }

        scenario("Create and delete host interface loop") {
            val ns = "foo"
            val proc = startVpp()
            Thread.sleep(1000) // only needed while first testcase is failing
            val api = new VppApi("test")
            try {
                createNamespace(ns)
                for (i <- 0 to 100) {
                    log.info(s"Creating interface, interation $i")
                    val device = Await.result(
                        api.createDevice(s"${ns}dp", None),
                        1 minute)
                    Await.result(api.deleteDevice(device),
                                 1 minute)
                }
            } finally {
                api.close()
                proc.destroy()
                deleteNamespace(ns)
            }
        }

        scenario("Setup interfaces, routes and ping, ipv6") {
            val nsLeft = "left"
            val nsRight = "right"
            val proc = startVpp()
            Thread.sleep(1000) // only needed while first testcase is failing
            val api = new VppApi("test")

            try {
                createNamespace(nsLeft)
                createNamespace(nsRight)

                log.info("Creating interfaces in vpp")
                val rightdp = Await.result(
                    api.createDevice(s"${nsRight}dp", None),
                    1 minute)
                val leftdp = Await.result(
                    api.createDevice(s"${nsLeft}dp", None),
                    1 minute)

                log.info("Adding addressing in left ns")
                assertCmdInNs(nsLeft, "ip a add 1001::1/64 dev lo")
                assertCmdInNs(nsLeft, s"ip a add 2001::1/64 dev ${nsLeft}ns")
                assertCmdInNs(nsLeft, "ip -6 r add default via 2001::2")

                log.info("Adding addressing in right ns")
                assertCmdInNs(nsRight, "ip a add 4001::1/64 dev lo")
                assertCmdInNs(nsRight, s"ip a add 3001::1/64 dev ${nsRight}ns")
                assertCmdInNs(nsRight, "ip -6 r add default via 3001::2")

                log.info("Adding addressing in vpp")
                Await.result(api.addDeviceAddress(
                                 leftdp, IPAddr.fromString("2001::2"), 64),
                             1 minute)
                Await.result(api.addDeviceAddress(
                                 rightdp, IPAddr.fromString("3001::2"), 64),
                             1 minute)

                log.info("Setting devices up")
                Await.result(api.setDeviceAdminState(leftdp, isUp=true),
                             1 minute)
                Await.result(api.setDeviceAdminState(rightdp, isUp=true),
                             1 minute)

                log.info("Add routes in vpp")
                Await.result(api.addRoute(IPSubnet.fromString("1001::/64"),
                                          Some(IPAddr.fromString("2001::1")),
                                          Some(leftdp)),
                             1 minute)
                Await.result(api.addRoute(IPSubnet.fromString("4001::/64"),
                                          Some(IPAddr.fromString("3001::1")),
                                          Some(rightdp)),
                             1 minute)

                log.info("pinging left to right")
                assertCmdInNs(nsLeft, "ping6 -c 5 4001::1")
                log.info("pinging right to left")
                assertCmdInNs(nsRight, "ping6 -c 5 1001::1")

                log.info("Deleting routes in vpp")
                Await.result(api.deleteRoute(IPSubnet.fromString("1001::/64"),
                                             Some(IPAddr.fromString("2001::1")),
                                             Some(leftdp)),
                             1 minute)
                Await.result(api.deleteRoute(IPSubnet.fromString("4001::/64"),
                                             Some(IPAddr.fromString("3001::1")),
                                             Some(rightdp)),
                             1 minute)

                log.info("Setting devices down")
                Await.result(api.setDeviceAdminState(leftdp, isUp=false),
                             1 minute)
                Await.result(api.setDeviceAdminState(rightdp, isUp=false),
                             1 minute)

                log.info("Deleting addresses in vpp")
                Await.result(api.deleteDeviceAddress(
                                 leftdp, IPAddr.fromString("2001::2"), 64),
                             1 minute)
                Await.result(api.deleteDeviceAddress(
                                 rightdp, IPAddr.fromString("3001::2"), 64),
                             1 minute)

                log.info("Deleting devices in vpp")
                Await.result(api.deleteDevice(leftdp), 1 minute)
                Await.result(api.deleteDevice(rightdp), 1 minute)
            } finally {
                api.close()
                proc.destroy()

                deleteNamespace(nsRight)
                deleteNamespace(nsLeft)
            }
        }

        scenario("Setup interfaces and ping, ipv4") {
            val nsLeft = "left"
            val nsRight = "right"
            val proc = startVpp()
            Thread.sleep(1000) // only needed while first testcase is failing
            val api = new VppApi("test")

            try {
                createNamespace(nsLeft)
                createNamespace(nsRight)

                log.info("Creating interfaces in vpp")
                val rightdp = Await.result(
                    api.createDevice(s"${nsRight}dp", None),
                    1 minute)
                val leftdp = Await.result(
                    api.createDevice(s"${nsLeft}dp", None),
                    1 minute)

                log.info("Adding addressing in left ns")
                assertCmdInNs(nsLeft, "ip a add 1.0.0.1/24 dev lo")
                assertCmdInNs(nsLeft, s"ip a add 2.0.0.1/24 dev ${nsLeft}ns")
                assertCmdInNs(nsLeft, "ip r add default via 2.0.0.2")

                log.info("Adding addressing in right ns")
                assertCmdInNs(nsRight, "ip a add 4.0.0.1/24 dev lo")
                assertCmdInNs(nsRight, s"ip a add 3.0.0.1/24 dev ${nsRight}ns")
                assertCmdInNs(nsRight, "ip r add default via 3.0.0.2")

                log.info("Adding addressing in vpp")
                Await.result(api.addDeviceAddress(
                                 leftdp, IPAddr.fromString("2.0.0.2"), 24),
                             1 minute)
                Await.result(api.addDeviceAddress(
                                 rightdp, IPAddr.fromString("3.0.0.2"), 24),
                             1 minute)

                log.info("Setting devices up")
                Await.result(api.setDeviceAdminState(leftdp, isUp=true),
                             1 minute)
                Await.result(api.setDeviceAdminState(rightdp, isUp=true),
                             1 minute)

                log.info("Add routes in vpp")
                Await.result(api.addRoute(IPSubnet.fromString("1.0.0.0/24"),
                                          Some(IPAddr.fromString("2.0.0.1")),
                                          Some(leftdp)),
                             1 minute)
                Await.result(api.addRoute(IPSubnet.fromString("4.0.0.0/24"),
                                          Some(IPAddr.fromString("3.0.0.1")),
                                          Some(rightdp)),
                             1 minute)

                log.info("pinging left to right")
                assertCmdInNs(nsLeft, "ping -c 5 4.0.0.1")
                log.info("pinging right to left")
                assertCmdInNs(nsRight, "ping -c 5 1.0.0.1")

                log.info("Deleting routes in vpp")
                Await.result(api.deleteRoute(IPSubnet.fromString("1.0.0.0/24"),
                                             Some(IPAddr.fromString("2.0.0.1")),
                                             Some(leftdp)),
                             1 minute)
                Await.result(api.deleteRoute(IPSubnet.fromString("4.0.0.0/24"),
                                             Some(IPAddr.fromString("3.0.0.1")),
                                             Some(rightdp)),
                             1 minute)

                log.info("Setting devices down")
                Await.result(api.setDeviceAdminState(leftdp, isUp=false),
                             1 minute)
                Await.result(api.setDeviceAdminState(rightdp, isUp=false),
                             1 minute)

                log.info("Deleting addresses in vpp")
                Await.result(api.deleteDeviceAddress(
                                 leftdp, IPAddr.fromString("2.0.0.2"), 24),
                             1 minute)
                Await.result(api.deleteDeviceAddress(
                                 rightdp, IPAddr.fromString("3.0.0.2"), 24),
                             1 minute)

                log.info("Deleting devices in vpp")
                Await.result(api.deleteDevice(leftdp), 1 minute)
                Await.result(api.deleteDevice(rightdp), 1 minute)
            } finally {
                api.close()
                proc.destroy()

                deleteNamespace(nsRight)
                deleteNamespace(nsLeft)
            }
        }
    }

    feature("VPP uplink Setup") {
        scenario("VPP sets up uplink port") {
            val uplinkns = "uplink2"

            val proc = startVpp()
            Thread.sleep(1000) // only needed while first testcase is failing
            val api = new VppApi("test")
            val datapath = createDatapath("foobar")
            val ovs = new VppOvs(datapath)

            log.info("Creating dummy uplink port")

            var setup: Option[VppUplinkSetup] = None
            try {
                createNamespace(uplinkns)
                val uplinkDp = ovs.createDpPort(s"${uplinkns}dp")

                setup = Some(new VppUplinkSetup(UUID.randomUUID,
                                                IPv6Addr("2001::1"),
                                                uplinkDp.getPortNo,
                                                api, ovs, log))
                assertCmdInNs(uplinkns, s"ip a add 2001::2/64 dev ${uplinkns}ns")

                setup foreach { s => Await.result(s.execute(), 1 minute) }
                assertCmdInNs(uplinkns, s"ping6 -c 5 2001::1")
            } finally {
                setup foreach { s => Await.result(s.rollback(), 1 minute) }
                deleteNamespace(uplinkns)
                deleteDatapath(datapath)
                api.close()
                proc.destroy()
            }
        }
    }

    feature("VPP downlink Setup") {
        scenario("VPP sets up downlink port") {
            setupStorage()
            log.info("Creating dummy tenant router with one port")
            val routerId: Option[UUID] = Some(UUID.randomUUID())
            val router = this.createRouter(routerId.get, None, Some("tenant1"))
            backend.store.create(router)

            val routerPortId = UUID.randomUUID()
            val port = this.createRouterPort(routerPortId, routerId)
            backend.store.create(port)
            val ovsDownlink = "ovs-"+ routerPortId.toString.substring(0, 8)

            val currentHostId = HostIdGenerator.getHostId
            log info "Adding current host to the storage"
            val host = this.createHost(currentHostId)
            backend.store.create(host)

            val proc = startVpp()
            Thread.sleep(1000)
            val api = new VppApi("test")

            var setup: Option[VppDownlinkSetup] = None
            try {
                val ip4 = IPv4Subnet.fromCidr("169.254.0.1/30")
                val ip6 = IPv6Subnet.fromString("2001::3/64")
                setup = Some(new VppDownlinkSetup(routerPortId, 0,
                                                  ip4, ip6, api, backend, log))
                setup foreach { s => Await.result(s.execute(), 1 minute) }
                assertCmd(s"ip a add 169.254.0.2/30 dev $ovsDownlink")
                log info "Pinging vpp interface"
                assertCmd(s"ping -c 5 ${ip4.getAddress}")
            } finally {
                setup foreach { s => Await.result(s.rollback(), 1 minute) }
                api.close()
                proc.destroy()
            }
        }
    }

    feature("VXLan downlink") {
        scenario("ping6 through vxlan") {
            val nsIPv6 = "ip6"
            val vethDownlink = "downlink"
            val datapathName = "foobar"
            val vxlanPort: Short = 4789
            val proc = startVpp()
            Thread.sleep(1000) // only needed while first testcase is failing
            val api = new VppApi("test")

            val bviMac = MAC.fromString("de:ad:be:ef:00:05")
            val nsMac = MAC.fromString("de:ad:be:ef:00:03")

            val datapath = createDatapath(datapathName)

            val vtepVpp = IPv4Addr.fromString("169.254.0.1")
            val vtepKern = IPv4Addr.fromString("169.254.0.2")

            var cleanupNs = Seq[String]()
            try {
                createNamespace(nsIPv6)

                createVethPair(vethDownlink)

                val ovs = new VppOvs(datapath)
                val vxlanDp = ovs.createVxlanDpPort("fip64", vxlanPort)

                // setup ipv6 side
                assertCmdInNs(nsIPv6,
                              "ip l set address de:ad:be:ef:00:01 dev ip6ns")
                assertCmdInNs(nsIPv6, "ip a add 2001::1/64 dev ip6ns")
                assertCmdInNs(nsIPv6, "ip a add 4001::1/64 dev lo")
                assertCmdInNs(nsIPv6,
                              "ip -6 r add 3001::/64 via 2001::2 src 4001::1")
                val ip6Dev = Await.result(api.createDevice("ip6dp", None),
                                          1 minute)
                Await.result(api.setDeviceAdminState(ip6Dev, isUp=true),
                             1 minute)
                Await.result(api.addDeviceAddress(
                                 ip6Dev, IPv6Addr.fromString("2001::2"), 64),
                             1 minute)
                Await.result(api.addRoute(
                                 IPv6Subnet.fromString("4001::/64"),
                                 nextHop=Some(IPv6Addr.fromString("2001::1")),
                                 device=Some(ip6Dev)), 1 minute)

                val downlinkDev = Await.result(
                    api.createDevice(s"${vethDownlink}-right", None),
                    1 minute)
                Await.result(api.setDeviceAdminState(downlinkDev, isUp=true),
                             1 minute)
                Await.result(api.addDeviceAddress(
                                 downlinkDev,
                                 IPv4Addr.fromString("169.254.0.1"), 24),
                             1 minute)
                assertCmd(s"ip a add ${vtepKern}/24 dev ${vethDownlink}-left")

                def addTenant(id: Int): IPv6Addr = {
                    val ns = s"tenant$id"
                    cleanupNs = cleanupNs :+ ns

                    createNamespace(ns)
                    val dp = ovs.createDpPort(s"${ns}dp")

                    val fmatch1 = new FlowMatch()
                    val fmask1 = new FlowMask()
                    val actions1 = new ArrayList[FlowAction]
                    fmatch1.addKey(FlowKeys.inPort(vxlanDp.getPortNo))
                    fmatch1.addKey(FlowKeys.tunnel(
                                      id, vtepVpp.toInt, vtepKern.toInt,  0))
                    fmatch1.addKey(FlowKeys.ethernet(bviMac.getAddress,
                                                    nsMac.getAddress))
                    fmatch1.getTunnelKey // to mark as seen
                    actions1.add(FlowActions.output(dp.getPortNo))
                    fmask1.calculateFor(fmatch1, actions1)
                    ovs.createFlow(datapath, fmatch1, fmask1, actions1)

                    val fmatch2 = new FlowMatch()
                    val fmask2 = new FlowMask()
                    val actions2 = new ArrayList[FlowAction]
                    fmatch2.addKey(FlowKeys.inPort(dp.getPortNo))
                    fmatch2.addKey(FlowKeys.ethernet(nsMac.getAddress,
                                                     bviMac.getAddress))
                    actions2.add(FlowActions.setKey(
                                    FlowKeys.tunnel(
                                        id, vtepKern.toInt, vtepVpp.toInt, 0)))
                    actions2.add(FlowActions.output(vxlanDp.getPortNo))
                    fmask2.calculateFor(fmatch2, actions2)
                    ovs.createFlow(datapath, fmatch2, fmask2, actions2)

                    assertCmdInNs(ns,
                                  s"ip l set address ${nsMac.toString}"
                                  + s" dev ${ns}ns")
                    assertCmdInNs(ns,
                                  s"ip a add 192.168.0.1/24 dev ${ns}ns")
                    assertCmdInNs(ns,
                                  "ip r add default via 192.168.0.2")
                    assertCmdInNs(ns,
                                  "ip neigh add 192.168.0.2 lladdr"
                                      + s" ${bviMac.toString} dev ${ns}ns")

                    val fip = IPv6Addr.fromString(s"3001::$id")
                    assertVppCtl(s"fip64 add ${fip} 192.168.0.1 "
                                 + "pool 10.0.0.1 10.0.0.1 "
                                 + s"table ${id}")

                    val setupVxlan = new VppVxlanTunnelSetup(id, id, api, log)
                    setupVxlan.execute()

                    fip
                }

                val addr1 = addTenant(1)
                assertCmdInNs(nsIPv6, s"ping6 -c 5 ${addr1}")

                val addr2 = addTenant(2)
                assertCmdInNs(nsIPv6, s"ping6 -c 5 ${addr2}")

                val addr3 = addTenant(3)
                assertCmdInNs(nsIPv6, s"ping6 -c 5 ${addr3}")
            } finally {
                proc.destroy()
                api.close()

                deleteVethPair(vethDownlink)
                cleanupNs foreach deleteNamespace
                deleteNamespace(nsIPv6)

                deleteDatapath(datapath)
            }
        }

        scenario("Can create Vxlan ovs port") {
            val datapath = createDatapath("foobar")
            val ovs = new VppOvs(datapath)
            ovs.createVxlanDpPort("vxlan_test_port", 5321)
        }
    }
}
