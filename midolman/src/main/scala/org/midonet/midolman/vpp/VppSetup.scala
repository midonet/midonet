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

import scala.collection.{Set, mutable}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.UnderlayResolver
import org.midonet.midolman.config.Fip64Config
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.odp.DpPort
import org.midonet.packets.{IPSubnet, _}
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}

object VppSetup extends MidolmanLogging {

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

    class VppIPv6Device(override val name: String,
                        deviceName: String,
                        vppApi: VppApi,
                        macSource: MacAddressProvider,
                        vrf: Int = 0)
                       (implicit ec: ExecutionContext)
        extends VppDevice(name, deviceName, vppApi, macSource, vrf)(ec) {

        /* IPv6 neighbour discovery needs to be disabled on a device before
           removing it or vpp will crash
         */
        override def rollback(): Future[Any] = {
            if (vppInterface.isDefined) {
                vppApi.disableIpv6Interface(vppInterface.get) flatMap { _ =>
                    super.rollback()
                }
            } else {
                super.rollback()
            }
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
            vppApi.createDevice(deviceName, macSource.macAddress)
                .flatMap { device =>
                    vppInterface = Some(device)
                    vppApi.setDeviceAdminState(device, isUp = true)
                        .flatMap { _ =>
                            vppApi.setDeviceTable(device, vrf, isIpv6 = false)
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
                         mac: MAC,
                         vppApi: VppApi)
                        (implicit ec: ExecutionContext)
    extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface: Option[VppApi.Device] = None

        override def execute(): Future[Any] = {
            vppApi.createLoopBackIf(Some(mac)) map  {
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
                               mac: MAC,
                               vppApi: VppApi)
                              (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        val neighbourMac = Some(mac)
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

    class VppAddRoute(override val name: String,
                        dst: IPSubnet[_ <: IPAddr],
                        nextHop: Option[IPAddr] = None,
                        nextDevice: Option[VppApi.Device] = None,
                        vrf: Int,
                        vppApi: VppApi)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            vppApi.addRoute(dst, nextHop, nextDevice, vrf)
        }

        override def rollback(): Future[Any] = {
            vppApi.deleteRoute(dst, nextHop, nextDevice, vrf)
        }
    }

    /* on rollback, removes all entries on a fip table in VPP
       This is required specially for IPv6 VRF tables, otherwise
       VPP crashes the next time it accesses the VRF table after
       a device with an IPv6 address is removed
     */
    class VppFipReset(override val name: String,
                      vrf: Int,
                      isIpv6: Boolean,
                      vppApi: VppApi)
                     (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            Future.successful(None)
        }

        override def rollback(): Future[Any] = {
            vppApi.resetFib(vrf, isIpv6 = isIpv6)
        }
    }
}

class VppSetup(setupName: String, log: Logger)
              (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback(setupName, log)(ec)

class VppUplinkSetup(uplinkPortId: UUID,
                     uplinkPortAddress: IPv6Addr,
                     uplinkPortDpNo: Int,
                     fip64Conf: Fip64Config,
                     flowStateConf: VppFlowStateConfig,
                     vppApi: VppApi,
                     vppOvs: VppOvs,
                     log: Logger)
                     (implicit ec: ExecutionContext)
    extends VppSetup("VPP uplink setup", log) {

    import VppSetup._

    private final val VppUplinkVRF = 0

    private val uplinkSuffix = uplinkPortId.toString.substring(0, 8)
    private val uplinkVppName = s"vpp-$uplinkSuffix"
    private val uplinkOvsName = s"ovs-$uplinkSuffix"

    private val uplinkVppPrefix: Byte = 64

    private val uplinkVeth = new VethPairSetup("uplink interface setup",
                                               uplinkVppName,
                                               uplinkOvsName)

    private val uplinkVpp = new VppIPv6Device("uplink VPP interface setup",
                                              uplinkVppName,
                                              vppApi,
                                              uplinkVeth)

    private val fipCleanup = new VppFipReset("cleanup vrf table",
                                             VppUplinkVRF,
                                             isIpv6 = true,
                                             vppApi)

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

    private val externalRoutes = new mutable.HashMap[Topology.Route, VppAddRoute]()

    private val flowStateOut = new VppFlowStateOutVxlanTunnelSetup(fip64Conf,
                                                                   flowStateConf,
                                                                   vppApi, log)

    private val flowStateIn = new VppFlowStateInVxlanTunnelSetup(fip64Conf,
                                                                 flowStateConf,
                                                                 vppApi, log)


    /*
     * setup the tasks, in execution order
     */
    add(uplinkVeth)
    add(uplinkVpp)
    add(fipCleanup)
    add(ipAddrVpp)
    add(ovsBind)
    add(ovsFlows)
    add(flowStateOut)
    add(flowStateIn)

    def addExternalRoute(route: Topology.Route): Future[Any] = {
        require(uplinkVpp.vppInterface.isDefined)
        val dst = IPSubnetUtil.fromProto(route.getDstSubnet).
            asInstanceOf[IPv6Subnet]
        externalRoutes.get(route) match {
            case None =>
                val nextHop = IPAddressUtil.toIPAddr(route.getNextHopGateway)
                val newVppRoute = new VppAddRoute("Add external route to VPP",
                                                  dst, Some(nextHop),
                                                  uplinkVpp.vppInterface,
                                                  0, vppApi)
                externalRoutes.put(route, newVppRoute)
                log debug s"Adding uplink route ${route.getId.asJava} " +
                          s"to interface ${uplinkVpp.vppInterface.get}"
                newVppRoute.execute()

            case _ =>
                deleteExternalRoute(route) flatMap {
                    _ => addExternalRoute(route)
                }
        }
    }

    def deleteExternalRoute(route: Topology.Route): Future[Any] = {
        externalRoutes.get(route) match {
            case None =>
                Future.successful(None)
            case Some(vppRoute) =>
                externalRoutes.remove(route)
                log debug s"Deleting external route ${route.getId.asJava}"
                vppRoute.rollback()
        }
    }

    def getExternalRoutes: Set[Topology.Route] = externalRoutes.keySet

    def deleteAllExternalRoutes(): Future[Any] = {
        val futures = for (route <- externalRoutes.keys) yield {
            deleteExternalRoute(route)
        }
        Future.sequence(futures)
    }
}

/**
  * @param vppApi handler for the JVPP
  * @param log logger
  * @param ec Execution context for futures
  */
class VppDownlinkSetup(config: Fip64Config,
                       vppApi: VppApi,
                       log: Logger)
                      (implicit ec: ExecutionContext)
    extends VppSetup("VPP downlink setup", log)(ec) {

    import VppSetup._

    private val dlinkSuffix = UUID.randomUUID().toString.substring(0, 8)
    private val dlinkVppName = s"vpp-dl-$dlinkSuffix"
    private val dlinkTunName = s"tun-dl-$dlinkSuffix"

    private val dlinkVeth = new VethPairSetup("downlink VXLAN interface setup",
                                              dlinkVppName,
                                              dlinkTunName,
                                              None,
                                              Some(config.vtepKernAddr))

    private val dlinkVpp = new VppDevice("downlink VXLAN VPP interface setup",
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
  */
class VppVxlanTunnelSetupPartial(name: String, config: Fip64Config,
                                 vni: Int, vrf: Int, vppApi: VppApi,
                                 log: Logger)
                                (implicit ec: ExecutionContext)
    extends VppSetup(name, log)(ec) {

    import VppSetup._

    val bridgeDomain = vrf

    private val vxlanDevice = new VxlanCreate("Create VXLAN tunnel",
                                              config.vtepVppAddr.getAddress,
                                              config.vtepKernAddr.getAddress,
                                              vni, vrf, vppApi)

    private val vxlanToBridge =
        new VxlanSetBridge("Set bridge domain for VXLAN device",
                           vxlanDevice,
                           bridgeDomain, false, vppApi)

    protected val loopBackDevice = new LoopBackCreate("Create loopback interface",
                                                      vni, vrf, config.vtepVppMac,
                                                      vppApi)

    private val loopToBridge =
        new VxlanSetBridge("Set bridge domain for loopback device",
                           loopBackDevice,
                           bridgeDomain, true, vppApi)

    private val loopToVrf =
        new VxlanLoopToVrf(s"Move loopback interface to VRF $vrf",
                           loopBackDevice,
                           vrf, vppApi)

    add(vxlanDevice)
    add(vxlanToBridge)
    add(loopBackDevice)
    add(loopToBridge)
    add(loopToVrf)
}

/**
  * @param vni VNI for this VXLAN
  * @param vrf VRF for the corresponding tenant router
  * Executes the following sequence of VPP commands:
  *     [commands from VppVxlanTunnelSetupPartial]
  *        - plus -
        set ip arp fib-id <vrf> loop0 172.16.0.1 dead.beef.0003
        ip route add table <vrf> 0.0.0.0/0 via 172.16.0.1
  */
class VppVxlanTunnelSetupBase(name: String, config: Fip64Config,
                              vni: Int, vrf: Int, routerPortMac: MAC,
                              vppApi: VppApi, log: Logger)
                             (implicit ec: ExecutionContext)
    extends VppVxlanTunnelSetupPartial(name, config, vni, vrf, vppApi, log)(ec) {

    import VppSetup._

    private val addLoopArpNeighbour =
        new VxlanAddArpNeighbour("Add ARP neighbour for loop interface",
                                 loopBackDevice,
                                 vrf, routerPortMac, vppApi)

    private val routefip64ToBridge =
        new VppAddRoute("Add default route to forward FIP64 to VXLAN bridge",
                          dst = IPSubnet.fromCidr("0.0.0.0/0").
                              asInstanceOf[IPv4Subnet],
                          nextHop = Some(IPv4Addr.fromString("172.16.0.1")),
                          nextDevice = None,
                          vrf, vppApi)

    add(addLoopArpNeighbour)
    add(routefip64ToBridge)
}

/**
  * Sets up the vxlan to tunnel traffic to a particular tenant router
  */
class VppVxlanTunnelSetup(config: Fip64Config,
                          vni: Int, vrf: Int, routerPortMac: MAC,
                          vppApi: VppApi, log: Logger)
                         (implicit ec: ExecutionContext)
    extends VppVxlanTunnelSetupBase("VXLAN tunnel setup", config, vni, vrf,
                                    routerPortMac, vppApi, log)(ec)


case class VppFlowStateConfig(vniOut: Int, vrfOut: Int,
                              vniIn: Int, vrfIn: Int)
/**
  * Same operations as VppVxlanTunnelSetup, just with a different log label.
  */
class VppFlowStateOutVxlanTunnelSetup(fip64conf: Fip64Config,
                                      fsConf: VppFlowStateConfig,
                                      vppApi: VppApi, log: Logger)
                                     (implicit ec: ExecutionContext)
    extends VppVxlanTunnelSetupBase("FlowState-out VXLAN tunnel setup",
                                    fip64conf, fsConf.vniOut, fsConf.vrfOut,
                                    fip64conf.portVppMac, vppApi, log)(ec)

/** Sets up the tunnel for incoming (into vpp) flow-state
  * Executes the following sequence of VPP commands:
  *     [commands from VppVxlanTunnelSetupPartial]
  *        - plus -
        set int ip address loopN 172.16.0.1/30
  */
class VppFlowStateInVxlanTunnelSetup(fip64conf: Fip64Config,
                                     fsConf: VppFlowStateConfig,
                                     vppApi: VppApi, log: Logger)
                                    (implicit ec: ExecutionContext)
    extends VppVxlanTunnelSetupPartial("FlowState-in VXLAN tunnel setup",
                                       fip64conf, fsConf.vniIn, fsConf.vrfIn,
                                       vppApi, log)(ec) {

    import VppSetup._

    private val setIpAddress = new VppIpAddr("Add loopback IP address",
                                             vppApi,
                                             loopBackDevice,
                                             IPv4Addr.fromString("172.16.0.1"),
                                             prefixLen = 30)
    add(setIpAddress)
}

class Fip64FlowStateFlows(vppOvs: VppOvs,
                          vppPort: Int,
                          underlayPorts: Seq[Int],
                          tunnelRoutes: Seq[UnderlayResolver.Route],
                          log: Logger)
                         (implicit ec: ExecutionContext)
        extends VppSetup("Fip64 flow state",  log)(ec) {

    class FlowStateSending
        extends FutureTaskWithRollback {
        override val name = "Flows for sending"

        override def execute(): Future[Any] = {
            try {
                vppOvs.addFlowStateSendingTunnelFlow(
                    vppPort, tunnelRoutes)
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        override def rollback(): Future[Any] = {
            try {
                vppOvs.clearFlowStateSendingTunnelFlow(vppPort)
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }
    }

    class FlowStateReceiving
            extends FutureTaskWithRollback {
        override val name = "Flows for receiving"

        override def execute(): Future[Any] = {
            try {
                underlayPorts foreach { p =>
                    vppOvs.addFlowStateReceivingTunnelFlow(p, vppPort)
                }
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        override def rollback(): Future[Any] = {
            try {
                underlayPorts foreach { p =>
                    vppOvs.clearFlowStateReceivingTunnelFlow(p)
                }
                Future.successful(Unit)
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }
    }

    add(new FlowStateSending)
    add(new FlowStateReceiving)
}
