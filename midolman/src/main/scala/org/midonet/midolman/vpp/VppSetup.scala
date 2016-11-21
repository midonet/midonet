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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.config.Fip64Config
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.odp.DpPort
import org.midonet.packets._
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}

private object VppSetup extends MidolmanLogging {

    override def logSource = s"org.midonet.vpp-controller"

    trait MacAddressProvider {
        def macAddress: Option[MAC]
    }

    trait VppInterfaceProvider {
        def vppInterface: Option[VppApi.Device]
    }

    class VethPairSetup(override val name: String,
                        devName: String,
                        peerName: String,
                        devAddress: Option[IPSubnet[_ <: IPAddr]] = None,
                        peerAddress: Option[IPSubnet[_ <: IPAddr]] = None)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with MacAddressProvider {

        var macAddress: Option[MAC] = None

        @throws[Exception]
        override def execute(): Future[Any] = Future {
            val veth = LinkOps.createVethPair(devName, peerName, up=true)
            devAddress match {
                case Some(address) => LinkOps.setAddress(veth.dev, address)
                case None => Unit
            }
            peerAddress match {
                case Some(address) => LinkOps.setAddress(veth.peer, address)
                case None => Unit
            }
            macAddress = Some(veth.dev.mac)
        }

        @throws[Exception]
        override def rollback(): Future[Any] = Future {
            LinkOps.deleteLink(devName)
        }
    }

    class VppDevice(override val name: String,
                    deviceName: String,
                    vppApi: VppApi,
                    macSource: MacAddressProvider,
                    vrf: Int = 0)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface: Option[VppApi.Device] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            val future = vppApi.createDevice(deviceName, macSource.macAddress)
                .flatMap { device =>
                    vppInterface = Some(device)
                    vppApi.setDeviceAdminState(device, isUp = true)
                }
            if (vrf == 0) {
                future
            } else {
                future.flatMap { _ =>
                    vppApi.setDeviceTable(vppInterface.get, vrf, isIpv6 = false)
                }
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            if (vppInterface.isDefined) {
                vppApi.deleteDevice(vppInterface.get) andThen { case _ =>
                    vppInterface = None
                }
            } else {
                Future.successful(Unit)
            }
        }
    }

    class VppIpAddr(override val name: String,
                    vppApi: VppApi,
                    device: VppInterfaceProvider,
                    address: IPAddr,
                    prefixLen: Byte)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        @throws[Exception]
        override def execute(): Future[Any] =
            vppApi.addDeviceAddress(device.vppInterface.get, address, prefixLen)

        @throws[Exception]
        override def rollback(): Future[Any] =
            vppApi.deleteDeviceAddress(device.vppInterface.get,
                                       address, prefixLen)
    }

    class OvsBindV6(override val name: String,
                    vppOvs: VppOvs,
                    endpointName: String)
        extends FutureTaskWithRollback {

        private var dpPort: Option[DpPort] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                dpPort = Some(vppOvs.createDpPort(endpointName))
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            dpPort match {
                case Some(port) => try {
                    vppOvs.deleteDpPort(port)
                    Future.successful(Unit)
                } catch {
                    case NonFatal(e) => Future.failed(e)
                }
                case _ =>
                    Future.successful(Unit)
            }
        }

        @throws[IllegalStateException]
        def getPortNo: Int = dpPort match {
            case Some(port) => port.getPortNo
            case _ => throw new IllegalStateException(
                s"Datapath port does not exist for $endpointName")
        }
    }

    class FlowInstall(override val name: String,
                      vppOvs: VppOvs,
                      ep1fn: () => Int,
                      ep2fn: () => Int)
        extends FutureTaskWithRollback {

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                vppOvs.addIpv6Flow(ep1fn(), ep2fn())
                vppOvs.addIpv6Flow(ep2fn(), ep1fn())
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            var firstException: Option[Throwable] = None
            try {
                vppOvs.clearIpv6Flow(ep1fn(), ep2fn())
            } catch {
                case NonFatal(e) => firstException = Some(e)
            }
            try {
                vppOvs.clearIpv6Flow(ep2fn(), ep1fn())
            } catch {
                case NonFatal(e) => if (firstException.isEmpty) {
                    firstException = Some(e)
                }
            }
            firstException match {
                case None => Future.successful(Unit)
                case Some(e) => Future.failed(e)
            }
        }
    }

    class OvsBindV4(override val name: String,
                    tenantRouterPortId: UUID,
                    ovsDownlinkPortName: String,
                    backend: MidonetBackend)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                backend.store.tryTransaction(tx =>  {
                    val hostId = HostIdGenerator.getHostId
                    val oldPort = tx.get(classOf[Port], tenantRouterPortId)
                    val newPort = oldPort.toBuilder
                        .setHostId(toProto(hostId))
                        .setInterfaceName(ovsDownlinkPortName)
                        .build()
                    tx.update(newPort)
                })

                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            try {
                backend.store.tryTransaction(tx => {
                    val currentPort = tx.get(classOf[Port], tenantRouterPortId)
                    val restoredPort = currentPort.toBuilder
                        .clearHostId()
                        .clearInterfaceName()
                        .build()
                    tx.update(restoredPort)
                })
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }
    }

    class VxlanCreate(override val name: String,
                      src: IPv4Addr, dst: IPv4Addr,
                      vni: Int, vrf: Int, vppApi: VppApi)
        (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface:Option[VppApi.Device] = None

        override def execute(): Future[Any] = {
            vppApi.addVxlanTunnel(src, dst, vni) map {
                result => vppInterface = Some(result)
            }
        }

        override def rollback(): Future[Any] =
            vppApi.delVxlanTunnel(src, dst, vni)
    }

    class VxlanSetBridge(override val name: String,
                         vxlanDevice: VppInterfaceProvider,
                         brId: Int, bvi: Boolean,
                         vppApi: VppApi)
                        (implicit ec: ExecutionContext)
    extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            require(vxlanDevice.vppInterface.isDefined)
            vppApi.setIfBridge(vxlanDevice.vppInterface.get,
                               brId, bvi)
        }

        override def rollback(): Future[Any] = {
            vppApi.unsetIfBridge(vxlanDevice.vppInterface.get,
                                 brId, bvi)
        }
    }

    class LoopBackCreate(override val name: String,
                         vni: Int, vrf: Int,
                         vppApi: VppApi)
                        (implicit ec: ExecutionContext)
    extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface: Option[VppApi.Device] = None

        override def execute(): Future[Any] = {
            vppApi.createLoopBackIf(Some(MAC.
                fromString("de:ad:be:ef:00:05"))) map  {
                result  => vppInterface = Some(result)
            }
        }

        override def rollback(): Future[Any] = {
            require(vppInterface.isDefined)
            vppApi.deleteLoopBackIf(vppInterface.get)
        }

    }

    class VxlanLoopToVrf(override val name: String,
                         loopDevice: VppInterfaceProvider,
                         vrf: Int,
                         vppApi: VppApi)
                        (implicit ec: ExecutionContext)
    extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            require(loopDevice.vppInterface.isDefined)
            vppApi.setDeviceTable(loopDevice.vppInterface.get,
                                  vrf, isIpv6 = false)
        }

        override def rollback(): Future[Any] = Future.successful(Unit)
    }

    class VxlanAddArpNeighbour(override val name: String,
                               loopDevice: VppInterfaceProvider,
                               vrf: Int,
                               vppApi: VppApi)
                              (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        val neighbourMac = Some(MAC.fromString("de:ad:be:ef:00:03"))
        override def execute(): Future[Any] = {
            require(loopDevice.vppInterface.isDefined)
            vppApi.addIfArpCacheEntry(loopDevice.vppInterface.get, vrf,
                                      IPv4Addr.fromString("172.16.0.1"),
                                      neighbourMac)
        }

        override def rollback(): Future[Any] = {
            vppApi.deleteIfArpCacheEntry(loopDevice.vppInterface.get, vrf,
                                         IPv4Addr.fromString("172.16.0.1"),
                                         neighbourMac)
        }
    }

    class VxlanAddRoute(override val name: String,
                        vrf: Int,
                        vppApi: VppApi)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            vppApi.addRoute(IPSubnet.fromCidr("0.0.0.0/0").
                asInstanceOf[IPv4Subnet],
                               nextHop = Some(IPv4Addr.fromString("172.16.0.1")),
                                None,
                                vrf)
        }

        override def rollback(): Future[Any] = {
            vppApi.deleteRoute(IPSubnet.fromCidr("0.0.0.0/0").
                asInstanceOf[IPv4Subnet],
                               nextHop = Some(IPv4Addr.fromString("172.16.0.1")),
                               None,
                               vrf)
        }
    }

}

class VppSetup(setupName: String, log: Logger)
              (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback(setupName, log)(ec)

class VppUplinkSetup(uplinkPortId: UUID,
                     uplinkPortAddress: IPv6Addr,
                     uplinkPortDpNo: Int,
                     vppApi: VppApi,
                     vppOvs: VppOvs,
                     log: Logger)
                     (implicit ec: ExecutionContext)
    extends VppSetup("VPP uplink setup", log) {

    import VppSetup._

    private val uplinkSuffix = uplinkPortId.toString.substring(0, 8)
    private val uplinkVppName = s"vpp-$uplinkSuffix"
    private val uplinkOvsName = s"ovs-$uplinkSuffix"

    private val uplinkVppPrefix: Byte = 64

    private val uplinkVeth = new VethPairSetup("uplink interface setup",
                                               uplinkVppName,
                                               uplinkOvsName)

    private val uplinkVpp = new VppDevice("uplink VPP interface setup",
                                          uplinkVppName,
                                          vppApi,
                                          uplinkVeth)

    private val ipAddrVpp = new VppIpAddr("uplink VPP IPv6 setup",
                                          vppApi,
                                          uplinkVpp,
                                          uplinkPortAddress,
                                          uplinkVppPrefix)

    private val ovsBind = new OvsBindV6("uplink OVS bindings",
                                        vppOvs,
                                        uplinkOvsName)
    private val ovsFlows = new FlowInstall("uplink OVS flows",
                                           vppOvs,
                                           () => { ovsBind.getPortNo },
                                           () => { uplinkPortDpNo })

    /*
     * setup the tasks, in execution order
     */
    add(uplinkVeth)
    add(uplinkVpp)
    add(ipAddrVpp)
    add(ovsBind)
    add(ovsFlows)
}

/**
  * @param downlinkPortId port id of the tenant router port that has IP6
  *                              address associated
  * @param vppApi handler for the JVPP
  * @param ec Execution context for futures
  */
class VppDownlinkSetup(downlinkPortId: UUID,
                       vrf: Int,
                       ip4Address: IPv4Subnet,
                       ip6Address: IPv6Subnet,
                       vppApi: VppApi,
                       backEnd: MidonetBackend,
                       log: Logger)
                       (implicit ec: ExecutionContext)
    extends VppSetup("VPP downlink setup", log)(ec) {

    import VppSetup._

    private val dlinkSuffix = downlinkPortId.toString.substring(0, 8)
    private val dlinkVppName = s"vpp-$dlinkSuffix"
    private val dlinkOvsName = s"ovs-$dlinkSuffix"

    private val dlinkVeth = new VethPairSetup("downlink interface setup",
                                              dlinkVppName,
                                              dlinkOvsName)

    private val dlinkVpp = new VppDevice("downlink VPP interface setup",
                                          dlinkVppName,
                                          vppApi,
                                          dlinkVeth,
                                          vrf)

    private val ipAddrVpp = new VppIpAddr("downlink VPP IPv6 setup",
                                          vppApi,
                                          dlinkVpp,
                                          ip4Address.getAddress,
                                          ip4Address.getPrefixLen.toByte)

    private val ovsBind = new OvsBindV4("downlink OVS binding",
                                        downlinkPortId,
                                        dlinkOvsName,
                                        backEnd)

    /*
     * setup the tasks, in execution order
     */
    add(dlinkVeth)
    add(dlinkVpp)
    add(ipAddrVpp)
    add(ovsBind)
}

/**
  * @param vppApi handler for the JVPP
  * @param log logger
  * @param ec Execution context for futures
  */
class VppDownlinkVxlanSetup(config: Fip64Config,
                            vppApi: VppApi,
                            log: Logger)
                           (implicit ec: ExecutionContext)
    extends VppSetup("VPP downlink Vxlan setup", log)(ec) {

    import VppSetup._

    private val uplinkSuffix = UUID.randomUUID().toString.substring(0, 8)
    private val dlinkVppName = s"vpp-dl-$uplinkSuffix"
    private val dlinkTunName = s"tun-dl-$uplinkSuffix"

    private val dlinkVeth = new VethPairSetup("downlink vxlan interface setup",
                                              dlinkVppName,
                                              dlinkTunName,
                                              None,
                                              Some(config.vtepKernAddr))

    private val dlinkVpp = new VppDevice("downlink vxlan VPP interface setup",
        dlinkVppName,
        vppApi,
        dlinkVeth)

    private val dlinkVppIp = new VppIpAddr("downlink VPP IPv4 setup",
        vppApi,
        dlinkVpp,
        config.vtepVppAddr.getAddress,
        config.vtepVppAddr.getPrefixLen.toByte)

    /*
     * setup the tasks, in execution order
     */
    add(dlinkVeth)
    add(dlinkVpp)
    add(dlinkVppIp)
}

/**
  * @param vni VNI for this VXLAN
  * @param vrf VRF for the corresponding tenant router
  * Executes the following sequence of VPP commands:
  *     create vxlan tunnel src 169.254.0.1 dst 169.254.0.2 vni 139
        loopback create mac de:ad:be:ef:00:05
        set int state loop0 up
        set int l2 bridge vxlan_tunnel0 <vni>
        set int l2 bridge loop0 <vni>  bvi
        set int ip table loop0 <vrf>
        set ip arp fib-id <vrf> loop0 172.16.0.1 dead.beef.0003
        ip route add table <vrf> 0.0.0.0/0 via 172.16.0.1
  */
class VppVxlanTunnelSetup(config: Fip64Config,
                          vni: Int, vrf: Int,
                          vppApi: VppApi, log: Logger)
                         (implicit ec: ExecutionContext)
    extends VppSetup("Vxlan tunnel setup",  log)(ec) {

    import VppSetup._

    private val vxlanDevice = new VxlanCreate("Create vxlan tunnel",
                                              config.vtepVppAddr.getAddress,
                                              config.vtepKernAddr.getAddress,
                                              vni, vrf, vppApi)

    private val vxlanToBridge =
        new VxlanSetBridge("Set bridge domain for vxlan device",
                           vxlanDevice,
                           vni, false, vppApi)

    private val loopBackDevice = new LoopBackCreate("Create loopback interface",
                                                    vni, vrf, vppApi)

    private val loopToBridge =
        new VxlanSetBridge("Set bridge domain for loopback device",
                           loopBackDevice,
                           vni, true, vppApi)

    private val loopToVrf =
        new VxlanLoopToVrf(s"Move loopback interface to VRF $vrf",
                           loopBackDevice,
                           vrf, vppApi)

    private val addLoopArpNeighbour =
        new VxlanAddArpNeighbour("Add 172.16.0.1 arp neighbour for loop interface",
                                 loopBackDevice,
                                 vrf, vppApi)

    private val routefip64ToBridge =
        new VxlanAddRoute("Add default route to forward fip64 to vxlan bridge",
                          vrf, vppApi)

    add(vxlanDevice)
    add(vxlanToBridge)
    add(loopBackDevice)
    add(loopToBridge)
    add(loopToVrf)
    add(addLoopArpNeighbour)
    add(routefip64ToBridge)
}
