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

import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import org.openvpp.jvpp.JVppRegistryImpl
import org.openvpp.jvpp.core.JVppCoreImpl
import org.openvpp.jvpp.core.dto._
import org.openvpp.jvpp.core.future.FutureJVppCoreFacade

import org.midonet.packets._

/**
  * Midonet wrapper for VPP java API
  */
object VppApi {
    case class Device(name: String, swIfIndex: Int)

    object Device {
        def apply(swIfId: Int):Device = new Device("", swIfId)
    }

    private def toScalaFuture[T](cs: CompletionStage[T])
                                (implicit ec: ExecutionContext): Future[T] = {
        val promise = Promise[T]
        cs.whenComplete(new BiConsumer[T, Throwable] {
            def accept(result: T, err: Throwable): Unit = {
                if (err eq null) {
                    promise.success(result)
                } else {
                    promise.failure(err)
                }
            }
        })
        promise.future
    }

    private def execVppRequest[In, Out](arg: In, f: In => CompletionStage[Out])
                                       (implicit
                                        ec: ExecutionContext): Future[Out] = {
        try {
            val cs = f(arg)
            toScalaFuture[Out](cs)
        } catch {
            case NonFatal(err) => Future.failed(err)
        }
    }

    private def boolToByte(inVal: Boolean): Byte = if (inVal) 1 else 0
}

class VppApi(connectionName: String)(implicit ec: ExecutionContext)
        extends AutoCloseable {

    import VppApi._

    private val registry = new JVppRegistryImpl(connectionName)
    private val coreLib = new JVppCoreImpl
    private val lib = new FutureJVppCoreFacade(registry, coreLib)

    def close(): Unit = {
        registry.close()
    }

    /** equivalent to:
     * vpp# delete host-interface name <name> */
    def deleteDevice(device: Device): Future[Any] = {
        val request = new AfPacketDelete
        request.hostIfName = device.name.toCharArray.map(_.toByte)
        execVppRequest(request, lib.afPacketDelete)
    }

    /** equivalent to:
    * vpp# create host-interface name <name> [hw-addr 01:02:03:04:05:06]*/
    def createDevice(name: String,
                     mac: Option[MAC]): Future[Device] = {

        val request = new AfPacketCreate
        request.hostIfName = name.toCharArray.map(_.toByte)
        mac match {
            case Some(addr) =>
                request.hwAddr = addr.getAddress
                request.useRandomHwAddr = 0
            case None =>
                request.useRandomHwAddr = 1
        }
        execVppRequest(request, lib.afPacketCreate) map {
            result => Device(name, result.swIfIndex)
        }
    }

    /** equivalent to:
      * vpp# set int ip table <device> <table>
      */
    def setDeviceTable(device: Device,
                       table: Int,
                       isIpv6: Boolean = false): Future[Any] = {
        val request = new SwInterfaceSetTable
        request.swIfIndex = device.swIfIndex
        request.isIpv6 = boolToByte(isIpv6)
        request.vrfId = table
        execVppRequest(request, lib.swInterfaceSetTable)
    }

    /** equivalent to:
     * vpp# set int state <interface> up */
    def setDeviceAdminState(device: Device,
                            isUp: Boolean): Future[Any] = {
        val setUpMsg = new SwInterfaceSetFlags
        setUpMsg.adminUpDown = boolToByte(isUp)
        setUpMsg.deleted = 0
        // not 100% sure what linkUpDown=0 means, but it can be seen
        // like this in example code, for example in:
        // https://wiki.fd.io/view/VPP/How_To_Use_The_API_Trace_Tools
        setUpMsg.linkUpDown = 0
        setUpMsg.swIfIndex = device.swIfIndex
        execVppRequest(setUpMsg, lib.swInterfaceSetFlags)
    }

    /** equivalent to:
      * vpp# create vxlan tunnel src <srcVtep> dst <dstVtep> vni <vni>
      */
    def addVxlanTunnel(srcVtep: IPv4Addr,
                       dstVtep: IPv4Addr,
                       vni: Int): Future[Device] =
        addDelVxlanTunnel(srcVtep, dstVtep, vni, isAdd = true) map {
            result => Device("vxlantunnel0", result.swIfIndex)
        }

    def delVxlanTunnel(srcVtep: IPv4Addr,
                       dstVtep: IPv4Addr,
                       vni: Int): Future[Any] =
        addDelVxlanTunnel(srcVtep, dstVtep, vni, isAdd = false)

    /** equivalent to:
      * vpp# loopback create [mac <mac>]
      * vpp# set int state <created_device_id> up
      */
    def createLoopBackIf(mac: Option[MAC]): Future[Device] = {
        val loopBackRequest = new CreateLoopback
        val finalMac = mac.getOrElse(MAC.random())
        loopBackRequest.macAddress = finalMac.getAddress()
        execVppRequest(loopBackRequest, lib.createLoopback) flatMap {
            result => {
                setDeviceAdminState(Device(result.swIfIndex),
                                    isUp = true) map
                (_ => Device(s"loop-$finalMac",result.swIfIndex))
            }
        }
    }

    def deleteLoopBackIf(device: Device): Future[Any] = {
        val request = new DeleteLoopback
        request.swIfIndex = device.swIfIndex
        execVppRequest(request, lib.deleteLoopback)
    }

    /** equivalent to:
      * vpp# set int l2 bridge <swIfId> <vni>  [bvi]
      */
    def setIfBridge(device: Device,
                    brId: Int,
                    bvi: Boolean): Future[Any] =
        setUnsetIfBridge(device, brId, bvi, set = true)

    def unsetIfBridge(device: Device,
                      brId: Int,
                      bvi: Boolean): Future[Any] =
        setUnsetIfBridge(device, brId, bvi, set = false)

    /**
      * equivalent to
      * vpp# set int ip address <device> <address>
      */
    def addDeviceAddress(device: Device,
                         address: IPAddr,
                         addressPrefixLen: Byte): Future[Any] =
        addDelDeviceAddress(device, address, addressPrefixLen, isAdd = true)

    def deleteDeviceAddress(device: Device,
                            address: IPAddr,
                            addressPrefixLen: Byte): Future[Any] =
        addDelDeviceAddress(device, address, addressPrefixLen, isAdd = false)

    /**
      * vpp# set ip arp fib-id <vrf> <swIfId> <ipAdd> <mac>
      */
    def addIfArpCacheEntry(device: Device,
                          vrf: Int,
                          ipAddr: IPv4Addr,
                          mac: Option[MAC]): Future[Any] =
        addDelIfArpCacheEntry(device, vrf, ipAddr, mac, isAdd = true)

    def deleteIfArpCacheEntry(device: Device,
                              vrf: Int,
                              ipAddr: IPv4Addr,
                              mac: Option[MAC]): Future[IpNeighborAddDelReply] =
        addDelIfArpCacheEntry(device, vrf, ipAddr, mac, isAdd = false)

    def resetFib(vrf: Int, isIpv6: Boolean = false): Future[Any] = {
        val request = new ResetFib
        request.isIpv6 = boolToByte(isIpv6)
        request.vrfId = vrf
        execVppRequest(request, lib.resetFib)
    }

    def disableIpv6Interface(device: Device): Future[Any] = {
        val disableIpv6Msg = new SwInterfaceIp6EnableDisable
        disableIpv6Msg.enable = boolToByte(false)
        disableIpv6Msg.swIfIndex = device.swIfIndex

        execVppRequest(disableIpv6Msg, lib.swInterfaceIp6EnableDisable)
    }

    /** equivalent to:
     * vpp# set int ip address <address>/<prefix> <interface> */
    private def addDelDeviceAddress(device: Device,
                                    address: IPAddr,
                                    addressPrefixLen: Byte,
                                    isAdd: Boolean,
                                    deleteAll: Boolean = false): Future[Any] = {
        val msg = new SwInterfaceAddDelAddress
        msg.address = address.toBytes
        msg.addressLength = addressPrefixLen
        msg.delAll = boolToByte(deleteAll)
        msg.isAdd = boolToByte(isAdd)
        msg.isIpv6 = boolToByte(address.isInstanceOf[IPv6Addr])
        msg.swIfIndex = device.swIfIndex

        execVppRequest(msg, lib.swInterfaceAddDelAddress)
    }

    /** equivalent to:
     * vpp# ip route add/del address/prefix via nextHop */
    private def addDelRoute(subnet: IPSubnet[_ <: IPAddr],
                            nextHop: Option[IPAddr],
                            nextHopDevice: Option[Device],
                            vrf: Int,
                            isAdd: Boolean,
                            multipath: Boolean = false,
                            isDrop: Boolean = false): Future[Any] = {
        val routeMsg = new IpAddDelRoute
        routeMsg.dstAddress = subnet.getAddress.toBytes
        routeMsg.dstAddressLength = subnet.getPrefixLen.toByte
        routeMsg.vrfId = vrf
        routeMsg.isAdd = boolToByte(isAdd)
        routeMsg.isDrop = boolToByte(isDrop)
        routeMsg.isIpv6 = boolToByte(subnet.isInstanceOf[IPv6Subnet])

        // createVrfIfNeeded / resolveIfNeeded / resolveAttempts:
        // seem not needed, but don't hurt.
        routeMsg.createVrfIfNeeded = 1
        routeMsg.resolveIfNeeded = 1
        routeMsg.resolveAttempts = 3 // got from sample

        // nextHopWeight is required to be at least 1
        routeMsg.nextHopWeight = 1

        // this seems to be set automatically when you add more than
        // one route to the same destination, not sure
        routeMsg.isMultipath = boolToByte(multipath)

        if (nextHop.isDefined) {
            routeMsg.nextHopAddress = nextHop.get.toBytes
        }
        if (nextHopDevice.isDefined) {
            routeMsg.nextHopSwIfIndex = nextHopDevice.get.swIfIndex
        }
        execVppRequest(routeMsg, lib.ipAddDelRoute)
    }

    def addRoute(subnet: IPSubnet[_ <: IPAddr],
                 nextHop: Option[IPAddr] = None,
                 device: Option[Device] = None,
                 vrf: Int = 0): Future[Any] = {
        addDelRoute(subnet, nextHop, device, vrf, isAdd=true)
    }

    def deleteRoute(subnet: IPSubnet[_ <: IPAddr],
                    nextHop: Option[IPAddr] = None,
                    device: Option[Device] = None,
                    vrf: Int = 0): Future[Any] = {
        addDelRoute(subnet, nextHop, device, vrf, isAdd=false)
    }

    def addDropRoute(subnet: IPSubnet[_ <: IPAddr],
                          vrf: Int = 0): Future[Any] = {
        addDelRoute(subnet, None, None, vrf, isAdd=true, isDrop = true)
    }

    /**
      * Adds given fip64 translation to vpp
      */
    def fip64Add(floatingIp: IPv6Addr,
                 fixedIp: IPv4Addr,
                 poolStart: IPv4Addr,
                 poolEnd: IPv4Addr,
                 vrf: Int, vni: Int): Future[Any] = {
        val request = new Fip64Add()
        request.fip6 = floatingIp.toBytes
        request.fixed4 = fixedIp.toBytes
        request.poolStart = poolStart.toBytes
        request.poolEnd = poolEnd.toBytes
        request.tableId = vrf
        request.vni = vni
        execVppRequest(request, lib.fip64Add)
    }

    def fip64Del(floatingIp: IPv6Addr): Future[Any] = {
        val request = new Fip64Del()
        request.fip6 = floatingIp.toBytes
        execVppRequest(request, lib.fip64Del)
    }

    /**
      * @param vrf vrf that should be used to send state flow packets
      * @return future to track asyncrhonous operation
      */
    def fip64SynEnable(vrf: Int) : Future[Any] = {
        val request = new Fip64SyncEnable()
        request.vrfId = vrf
        execVppRequest(request, lib.fip64SyncEnable)
    }

    def fip64SyncDisable: Future[Any] = {
        execVppRequest(new Fip64SyncDisable(), lib.fip64SyncDisable)
    }

    private def addDelVxlanTunnel(srcVtep: IPv4Addr,
                                  dstVtep: IPv4Addr,
                                  vni: Int,
                                  isAdd: Boolean): Future[VxlanAddDelTunnelReply] = {
        val request = new VxlanAddDelTunnel()
        request.isAdd = boolToByte(isAdd)
        request.isIpv6 = 0
        request.srcAddress = srcVtep.toBytes
        request.dstAddress = dstVtep.toBytes
        request.vni = vni
        request.encapVrfId = 0 // Always send encapsulation result via VRF 0
        request.decapNextIndex = ~0 // Default node for decapsulation is l2-output
        execVppRequest(request, lib.vxlanAddDelTunnel)
    }

    private def setUnsetIfBridge(device: Device,
                                 brId: Int,
                                 bvi: Boolean,
                                 set: Boolean): Future[SwInterfaceSetL2BridgeReply] = {
        val request = new SwInterfaceSetL2Bridge()
        request.bdId = brId
        request.rxSwIfIndex = device.swIfIndex
        request.shg = 0
        request.bvi = boolToByte(bvi)
        request.enable = boolToByte(set)
        execVppRequest(request, lib.swInterfaceSetL2Bridge)
    }

    private def addDelIfArpCacheEntry(device: Device,
                                      vrf: Int,
                                      ipAddr: IPv4Addr,
                                      mac: Option[MAC],
                                      isAdd: Boolean):
        Future[IpNeighborAddDelReply] = {
        val request = new IpNeighborAddDel
        request.swIfIndex = device.swIfIndex
        request.vrfId = vrf
        request.dstAddress = ipAddr.toBytes
        request.macAddress = mac.getOrElse(MAC.random).getAddress
        request.isAdd = boolToByte(isAdd)
        request.isIpv6 = 0
        request.isStatic = 0
        execVppRequest(request, lib.ipNeighborAddDel)
    }

}

