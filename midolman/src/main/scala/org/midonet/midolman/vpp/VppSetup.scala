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
import org.midonet.midolman.vpp.VppSetup.VppIpAddr
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.odp.DpPort
import org.midonet.packets.{IPSubnet, _}
import org.midonet.util.EthtoolOps
import org.midonet.util.concurrent.FutureTaskWithRollback._
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}

object VppSetup extends MidolmanLogging {

    override def logSource = s"org.midonet.vpp-controller"

    private[vpp] final val SuccessfulFuture = Future.successful(Unit)
    private[vpp] final val VppUplinkVrf = 0

    class MacAddressProvider(var macAddress: Option[MAC] = None) {
        override def toString = {
            if (macAddress.isDefined) macAddress.get.toString
            else "MAC undefined"
        }
    }

    trait VppInterfaceProvider {
        def vppInterface: Option[VppApi.Device]
    }

    class VethPairSetup(override val name: TaskName,
                        devName: String,
                        peerName: String,
                        devAddress: Option[IPSubnet[_ <: IPAddr]] = None,
                        peerAddress: Option[IPSubnet[_ <: IPAddr]] = None)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        var devMac = new MacAddressProvider()
        var peerMac = new MacAddressProvider()

        @throws[Exception]
        override def execute(): Future[Any] = Future {
            val veth = LinkOps.createVethPair(devName, peerName, up=true)
            devAddress match {
                case Some(address) => LinkOps.setAddress(veth.dev, address)
                case None =>
            }
            peerAddress match {
                case Some(address) => LinkOps.setAddress(veth.peer, address)
                case None =>
            }
            devMac.macAddress = Some(veth.dev.mac)
            peerMac.macAddress = Some(veth.peer.mac)
        }

        @throws[Exception]
        override def rollback(): Future[Any] = Future {
            LinkOps.deleteLink(devName)
        }
    }

    class VppIPv6Device(override val name: TaskName,
                        deviceName: String,
                        vppApi: VppApi,
                        sourceMac: MacAddressProvider,
                        vrf: Int = 0)
                       (implicit ec: ExecutionContext)
        extends VppDevice(name, deviceName, vppApi, sourceMac, vrf)(ec) {

        /** IPv6 neighbour discovery needs to be disabled on a device before
          * removing it or vpp will crash.
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

    class VppDevice(override val name: TaskName,
                    deviceName: String,
                    vppApi: VppApi,
                    sourceMac: MacAddressProvider,
                    vrf: Int = 0)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface: Option[VppApi.Device] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            vppApi.createDevice(deviceName, sourceMac.macAddress)
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
                SuccessfulFuture
            }
        }
    }

    class VppIpAddr(override val name: TaskName,
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

    class OvsBindV6(override val name: TaskName,
                    vppOvs: VppOvs,
                    endpointName: String)
        extends FutureTaskWithRollback {

        private var dpPort: Option[DpPort] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                dpPort = Some(vppOvs.createDpPort(endpointName))
                SuccessfulFuture
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            dpPort match {
                case Some(port) => try {
                    vppOvs.deleteDpPort(port)
                    SuccessfulFuture
                } catch {
                    case NonFatal(e) => Future.failed(e)
                }
                case _ =>
                    SuccessfulFuture
            }
        }

        @throws[IllegalStateException]
        def getPortNo: Int = dpPort match {
            case Some(port) => port.getPortNo
            case _ => throw new IllegalStateException(
                s"Datapath port does not exist for $endpointName")
        }
    }

    class FlowInstall(override val name: TaskName,
                      vppOvs: VppOvs,
                      ep1fn: () => Int,
                      ep2fn: () => Int)
        extends FutureTaskWithRollback {

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                vppOvs.addIpv6Flow(ep1fn(), ep2fn())
                vppOvs.addIpv6Flow(ep2fn(), ep1fn())
                SuccessfulFuture
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
                case None => SuccessfulFuture
                case Some(e) => Future.failed(e)
            }
        }
    }

    class VxlanCreate(override val name: TaskName,
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

    class VxlanSetBridge(override val name: TaskName,
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

    class LoopbackCreate(override val name: TaskName,
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

    class VxlanLoopToVrf(override val name: TaskName,
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

        override def rollback(): Future[Any] = SuccessfulFuture
    }

    class VxlanAddArpNeighbour(override val name: TaskName,
                               loopDevice: VppInterfaceProvider,
                               vrf: Int,
                               address: IPv4Addr,
                               neighbourMac: MacAddressProvider,
                               vppApi: VppApi)
                              (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            require(loopDevice.vppInterface.isDefined)
            vppApi.addIfArpCacheEntry(loopDevice.vppInterface.get, vrf,
                                      address,
                                      neighbourMac.macAddress)
        }

        override def rollback(): Future[Any] = {
            vppApi.deleteIfArpCacheEntry(loopDevice.vppInterface.get, vrf,
                                         address,
                                         neighbourMac.macAddress)
        }
    }

    class VppAddRoute(override val name: TaskName,
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
    class VppFipReset(override val name: TaskName,
                      vrf: Int,
                      isIpv6: Boolean,
                      vppApi: VppApi)
                     (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            SuccessfulFuture
        }

        override def rollback(): Future[Any] = {
            vppApi.resetFib(vrf, isIpv6 = isIpv6)
        }
    }

    class ChecksumOffloading(override val name: TaskName,
                             interfaceName: String,
                             enabled: Boolean)
        extends FutureTaskWithRollback {

        override def execute(): Future[Any] = {
            try {
                EthtoolOps.setTxChecksum(interfaceName, enabled)
                EthtoolOps.setRxChecksum(interfaceName, enabled)
                SuccessfulFuture
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        override def rollback(): Future[Any] = {
            SuccessfulFuture
        }
    }
}

class VppSetup(name: String, log: Logger)
              (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback(name, log)(ec)

class VppUplinkSetup(uplinkPortId: UUID,
                     uplinkPortAddress: IPv6Addr,
                     uplinkPortDpNo: Int,
                     fip64Conf: Fip64Config,
                     flowStateConf: VppFlowStateConfig,
                     vppApi: VppApi,
                     vppOvs: VppOvs,
                     log: Logger)
                     (implicit ec: ExecutionContext)
    extends VppSetup("uplink setup", log) {

    import VppSetup._

    private val vppctl = new VppCtl(log)

    private val uplinkSuffix = uplinkPortId.toString.substring(0, 8)
    private val uplinkVppName = s"vpp-ul-$uplinkSuffix"
    private val uplinkOvsName = s"ovs-ul-$uplinkSuffix"

    private val uplinkVppPrefix: Byte = 64

    private val externalRoutes =
        new mutable.HashMap[Topology.Route, VppAddRoute]()

    private val veth =
        new VethPairSetup(
            s"setup uplink interfaces $uplinkVppName $uplinkOvsName",
            uplinkVppName,
            uplinkOvsName)

    private val device =
        new VppIPv6Device(
            s"setup uplink VPP interface $uplinkVppName ${veth.devMac}",
            uplinkVppName,
            vppApi,
            veth.devMac)

    private val fipCleanup =
        new VppFipReset(
            s"cleanup VRF table ${VppSetup.VppUplinkVrf}",
            VppSetup.VppUplinkVrf,
            isIpv6 = true,
            vppApi)

    private val ipAddress =
        new VppIpAddr(
            s"setup uplink IPv6 address $uplinkPortAddress",
            vppApi,
            device,
            uplinkPortAddress,
            uplinkVppPrefix)

    private val ovsBind =
        new OvsBindV6(
            s"uplink OVS bindings for $uplinkOvsName",
            vppOvs,
            uplinkOvsName)

    private val ovsFlows =
        new FlowInstall(
            s"setup uplink OVS flows between ports ${ovsBind.getPortNo} " +
            s"$uplinkPortDpNo",
            vppOvs,
            () => { ovsBind.getPortNo },
            () => { uplinkPortDpNo })

    private val flowStateOut =
        new VppFlowStateOutVxlanTunnelSetup(fip64Conf,
                                            flowStateConf,
                                            vppApi, log)

    private val flowStateIn =
        new VppFlowStateInVxlanTunnelSetup(fip64Conf,
                                           flowStateConf,
                                           vppApi, log)

    private val enableFlowstate = new FutureTaskWithRollback {

        override def name = "enable flow state"

        @throws[Exception]
        override def execute() = {
            vppctl.exec(s"fip64 sync ${flowStateConf.vrfOut}")
        }

        @throws[Exception]
        override def rollback() = {
            vppctl.exec(s"fip64 sync disable")
        }
    }

    /*
     * setup the tasks, in execution order
     */
    add(veth)
    add(device)
    add(fipCleanup)
    add(ipAddress)
    add(ovsBind)
    add(ovsFlows)
    add(flowStateOut)
    add(flowStateIn)
    add(enableFlowstate)

    def addExternalRoute(route: Topology.Route): Future[Any] = {
        require(device.vppInterface.isDefined)
        val dst = IPSubnetUtil.fromProto(route.getDstSubnet).
            asInstanceOf[IPv6Subnet]
        externalRoutes.get(route) match {
            case None =>
                val nextHop = IPAddressUtil.toIPAddr(route.getNextHopGateway)
                val newVppRoute = new VppAddRoute("add uplink external route",
                                                  dst, Some(nextHop),
                                                  device.vppInterface,
                                                  0, vppApi)
                externalRoutes.put(route, newVppRoute)
                log debug s"Adding uplink route ${route.getId.asJava} " +
                          s"to interface ${device.vppInterface.get}"
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
                SuccessfulFuture
            case Some(vppRoute) =>
                externalRoutes.remove(route)
                log debug s"Deleting uplink external route ${route.getId.asJava}"
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
    extends VppSetup("downlink setup", log)(ec) {

    import VppSetup._

    private val downlinkSuffix = UUID.randomUUID().toString.substring(0, 8)
    private val downlinkVppName = s"vpp-dl-$downlinkSuffix"
    private val downlinkTunName = s"tun-dl-$downlinkSuffix"

    private val veth =
        new VethPairSetup(
            s"setup downlink interfaces $downlinkVppName $downlinkTunName",
            downlinkVppName,
            downlinkTunName,
            devAddress = None,
            peerAddress = Some(config.vtepKernAddr))

    private val disableOffloading =
        new ChecksumOffloading(
            s"disable checksum offloading on $downlinkTunName",
            downlinkTunName,
            enabled = false)

    private val device =
        new VppDevice(
            s"setup downlink VPP interface $downlinkVppName ${veth.devMac}",
            downlinkVppName,
            vppApi,
            veth.devMac)

    private val ipAddress =
        new VppIpAddr(
            s"setup downlink IPv4 address ${config.vtepVppAddr}",
            vppApi,
            device,
            config.vtepVppAddr.getAddress,
            config.vtepVppAddr.getPrefixLen.toByte)

    /**
      * Add an ARP entry in table 0 for the tunnel-interface peer (OVS side)
      * so that the first flow-state mapping notification is not dropped due
      * to ARP resolution.
      */
    private val arpNeighbour =
        new VxlanAddArpNeighbour(
            s"add ARP neighbour ${config.vtepKernAddr} ${veth.peerMac} " +
            s"to downlink interface",
            device,
            vrf = 0,
            config.vtepKernAddr.getAddress,
            veth.peerMac,
            vppApi)

    add(veth)
    add(disableOffloading)
    add(device)
    add(ipAddress)
    add(arpNeighbour)
}

/**
  * @param vni VNI for this VXLAN
  * @param vrf VRF for the corresponding tenant router
  * Executes the following sequence of VPP commands:
  *     create vxlan tunnel src 169.254.0.1 dst 169.254.0.2 vni 139
  *     loopback create mac de:ad:be:ef:00:05
  *     set int state loop0 up
  *     set int l2 bridge vxlan_tunnel0 <vni>
  *     set int l2 bridge loop0 <vni>  bvi
  *     set int ip table loop0 <vrf>
  */
class VppVxlanTunnelSetupPartial(name: String, config: Fip64Config,
                                 loopbackMac: MAC,
                                 vni: Int, vrf: Int, vppApi: VppApi,
                                 log: Logger)
                                (implicit ec: ExecutionContext)
    extends VppSetup(name, log)(ec) {

    import VppSetup._

    private def bridgeDomain = vrf

    private val vxlanDevice =
        new VxlanCreate(
            s"create VXLAN tunnel with VNI $vni",
            config.vtepVppAddr.getAddress,
            config.vtepKernAddr.getAddress,
            vni, vrf, vppApi)

    private val vxlanToBridge =
        new VxlanSetBridge(
            s"setup bridge domain $bridgeDomain for VXLAN interface",
            vxlanDevice,
            bridgeDomain, false, vppApi)

    protected val loopbackDevice =
        new LoopbackCreate(
            s"create loopback interface $loopbackMac",
            loopbackMac, vppApi)

    private val loopToBridge =
        new VxlanSetBridge(
            s"setup bridge domain $bridgeDomain for loopback interface",
            loopbackDevice,
            bridgeDomain, true, vppApi)

    private val loopToVrf =
        new VxlanLoopToVrf(
            s"move loopback interface to VRF $vrf",
            loopbackDevice, vrf, vppApi)

    add(vxlanDevice)
    add(vxlanToBridge)
    add(loopbackDevice)
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
  */
class VppVxlanTunnelSetupBase(name: String, config: Fip64Config,
                              vni: Int, vrf: Int, routerPortMac: MAC,
                              vppApi: VppApi, log: Logger)
                             (implicit ec: ExecutionContext)
    extends VppVxlanTunnelSetupPartial(name, config, config.vtepVppMac,
                                       vni, vrf, vppApi, log)(ec) {

    import VppSetup._

    private val loopArpNeighbour =
        new VxlanAddArpNeighbour(
            s"add ARP neighbour ${config.vppInternalGateway} $routerPortMac " +
            s"to loopback interface",
            loopbackDevice,
            vrf,
            config.vppInternalGateway,
            new MacAddressProvider(Some(routerPortMac)),
            vppApi)

    add(loopArpNeighbour)
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
  * Same operations as VppVxlanTunnelSetup, plus default route to l2 bridge
  * ip route add table VRF 0.0.0.0/0 via 172.16.0.1
  */
class VppFlowStateOutVxlanTunnelSetup(fip64conf: Fip64Config,
                                      fsConf: VppFlowStateConfig,
                                      vppApi: VppApi, log: Logger)
                                     (implicit ec: ExecutionContext)
    extends VppVxlanTunnelSetupBase("out flow state VXLAN tunnel setup",
                                    fip64conf, fsConf.vniOut, fsConf.vrfOut,
                                    fip64conf.portVppMac, vppApi, log)(ec) {
    import VppSetup.VppAddRoute

    private val fip64ToBridgeRoute =
        new VppAddRoute(
            "add default route to forward FIP64 to VXLAN bridge",
            dst = IPv4Subnet.ANY,
            nextHop = Some(fip64conf.vppInternalGateway),
            nextDevice = None,
            fsConf.vrfOut, vppApi)

    add(fip64ToBridgeRoute)
}

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
    extends VppVxlanTunnelSetupPartial("in flow state VXLAN tunnel setup",
                                       fip64conf, fip64conf.portVppMac,
                                       fsConf.vniIn, fsConf.vrfIn,
                                       vppApi, log)(ec) {

    private val ipAddress =
        new VppIpAddr(
            s"add loopback IP address ${fip64conf.vppInternalGateway}",
            vppApi,
            loopbackDevice,
            fip64conf.vppInternalGateway,
            prefixLen = 30)

    add(ipAddress)
}

class Fip64FlowStateFlows(vppOvs: VppOvs,
                          vppPort: Int,
                          underlayPorts: Seq[Int],
                          tunnelRoutes: Seq[UnderlayResolver.Route],
                          log: Logger)
                         (implicit ec: ExecutionContext)
        extends VppSetup("FIP64 flow state",  log)(ec) {

    private object FlowStateSending extends FutureTaskWithRollback {
        override def name = "flows for sending"

        override def execute(): Future[Any] = {
            try {
                vppOvs.addFlowStateSendingTunnelFlow(
                    vppPort, tunnelRoutes)
                VppSetup.SuccessfulFuture
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        override def rollback(): Future[Any] = {
            try {
                vppOvs.clearFlowStateSendingTunnelFlow(vppPort)
                VppSetup.SuccessfulFuture
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }
    }

    private object FlowStateReceiving extends FutureTaskWithRollback {
        override def name = "flows for receiving"

        override def execute(): Future[Any] = {
            try {
                underlayPorts foreach { p =>
                    vppOvs.addFlowStateReceivingTunnelFlow(p, vppPort)
                }
                VppSetup.SuccessfulFuture
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }

        override def rollback(): Future[Any] = {
            try {
                underlayPorts foreach { p =>
                    vppOvs.clearFlowStateReceivingTunnelFlow(p)
                }
                VppSetup.SuccessfulFuture
            } catch {
                case NonFatal(e) => Future.failed(e)
            }
        }
    }

    add(FlowStateSending)
    add(FlowStateReceiving)
}
