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
    case class Device(name: String, swIfIndex: Int) {
        override def toString = name + s" swid: $swIfIndex"
    }

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
                            multipath: Boolean = false): Future[Any] = {
        val routeMsg = new IpAddDelRoute
        routeMsg.dstAddress = subnet.getAddress.toBytes
        routeMsg.dstAddressLength = subnet.getPrefixLen.toByte
        routeMsg.vrfId = vrf
        routeMsg.isAdd = boolToByte(isAdd)
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
                 vrf: Int = 0,
                 multipath: Boolean = false): Future[Any] = {
        addDelRoute(subnet, nextHop, device, vrf, isAdd=true, multipath)
    }

    def deleteRoute(subnet: IPSubnet[_ <: IPAddr],
                    nextHop: Option[IPAddr] = None,
                    device: Option[Device] = None,
                    vrf: Int = 0): Future[Any] = {
        addDelRoute(subnet, nextHop, device, vrf, isAdd=false)
    }

    /** equivalent to:
     * map create domain ip4-pfx 10.0.0.1/24 ip6-pfx 2016::/32 ip6-src 2017::/96 \
     *    ea-bits-len 8 psid-len 0 psid-offset 0 map-t mtu 0
     * Usage example:
     *      val api = new VppApi("test1") // VPP process must be running
            val ip6p = Array[Byte](0x20, 0x16,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
            val ip4p = Array[Byte](0xa, 0, 0, 1)
            val ip6src = Array[Byte](0x20, 0x17, 0,0,0,0,0,0,0,0,0,0,0,0,0,0)
            val f = api.mapAddDomain(ip6p, ip4p, ip6src)
            f onComplete {
              case Success(status) => println("Created domain with index " + status.index); System.exit(0)
              case Failure(t) => println("An error has occured: " + t.getMessage)
            }
    */
    def mapAddDomain(
                        ip6Prefix: Array[Byte],
                        ip4Prefix: Array[Byte],
                        ip6Src: Array[Byte],
                        ip6PrefixLen: Byte = 32,
                        ip4PrefixLen: Byte = 24,
                        ip6SrcPrefixLen: Byte = 96,
                        eaBitsLen: Byte = 8,
                        psidOffset: Byte = 0,
                        psidLength: Byte = 0,
                        isTranslation: Boolean = true,
                        mtu: Short = 0): Future[MapAddDomainReply] = {
        require(ip6SrcPrefixLen ==
                96) // VPP map-t only supports 96 bits Default Mapping Rules
        require(ip6PrefixLen + eaBitsLen <=
                64) // VPP map-t performs all decoding on DWord 1 of 128-bit address
        val request = new MapAddDomain()
        request.ip6Prefix = ip6Prefix
        request.ip4Prefix = ip4Prefix
        request.ip6Src = ip6Src
        request.ip6PrefixLen = ip6PrefixLen
        request.ip4PrefixLen = ip4PrefixLen
        request.ip6SrcPrefixLen = ip6SrcPrefixLen
        request.eaBitsLen = eaBitsLen
        request.psidOffset = psidOffset
        request.psidLength = psidLength
        request.isTranslation = if (isTranslation) {
            1
        } else {
            0
        }
        execVppRequest(request, lib.mapAddDomain)
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

