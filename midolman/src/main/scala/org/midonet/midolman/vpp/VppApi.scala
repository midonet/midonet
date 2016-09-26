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

/**
  * Midonet wrapper for VPP java API
  */
object VppApi {

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
}

class VppApi(connectionName: String)(implicit ec: ExecutionContext) {

    import VppApi._

    private val registry = new JVppRegistryImpl(connectionName)
    private val coreLib = new JVppCoreImpl
    private val lib = new FutureJVppCoreFacade(registry, coreLib)

    /** equivalent to:
     * vpp# delete host-interface name <name> */
    def deleteDevice(name: String): Future[AfPacketDeleteReply] = {
        val request = new AfPacketDelete
        request.hostIfName = name.toCharArray.map(_.toByte)
        execVppRequest(request, lib.afPacketDelete)
    }

    /** equivalent to:
    * vpp# create host-interface name <name> [hw-addr 01:02:03:04:05:06]*/
    def createDevice(name: String,
                     mac: Option[Array[Byte]]): Future[AfPacketCreateReply] = {

        val request = new AfPacketCreate
        request.hostIfName = name.toCharArray.map(_.toByte)
        mac match {
            case Some(addr) =>
                request.hwAddr = addr
                request.useRandomHwAddr = 0
            case None =>
                request.useRandomHwAddr = 1
        }
        execVppRequest(request, lib.afPacketCreate)
    }

    /** equivalent to:
     * vpp# set int state <interface> up */
    def setDeviceAdminState(ifIndex: Int,
                            isUp: Boolean): Future[SwInterfaceSetFlagsReply] = {
        val setUpMsg = new SwInterfaceSetFlags
        setUpMsg.adminUpDown = if (isUp) {
            1
        } else {
            0
        }
        setUpMsg.deleted = 0
        // not 100% sure what linkUpDown=0 means, but it can be seen
        // like this in example code, for example in:
        // https://wiki.fd.io/view/VPP/How_To_Use_The_API_Trace_Tools
        setUpMsg.linkUpDown = 0
        setUpMsg.swIfIndex = ifIndex
        execVppRequest(setUpMsg, lib.swInterfaceSetFlags)
    }

    /** equivalent to:
     * vpp# set int ip address <address>/<prefix> <interface> */
    def addDelDeviceAddress(ifIndex: Int,
                            address: Array[Byte],
                            addressLength: Byte,
                            isIpv6: Boolean,
                            isAdd: Boolean,
                            deleteAll: Boolean = false)
    : Future[SwInterfaceAddDelAddressReply] = {
        val msg = new SwInterfaceAddDelAddress
        msg.address = address
        msg.addressLength = addressLength
        msg.delAll = if (deleteAll) {
            1
        } else {
            0
        }
        msg.isAdd = if (isAdd) {
            1
        } else {
            0
        }
        msg.isIpv6 = if (isIpv6) {
            1
        } else {
            0
        }
        msg.swIfIndex = ifIndex
        execVppRequest(msg, lib.swInterfaceAddDelAddress)
    }

    /** equivalent to:
     * ip route add/del address/prefix via nextHop */
    def addDelRoute(address: Array[Byte],
                    prefix: Byte,
                    nextHop: Option[Array[Byte]],
                    device: Option[Int],
                    isAdd: Boolean,
                    isIpv6: Boolean,
                    multiplath: Boolean = false): Future[IpAddDelRouteReply] = {
        val routeMsg = new IpAddDelRoute
        routeMsg.dstAddress = address
        routeMsg.dstAddressLength = prefix
        routeMsg.isAdd = if (isAdd) {
            1
        } else {
            0
        }
        routeMsg.isIpv6 = if (isIpv6) {
            1
        } else {
            0
        }

        // createVrfIfNeeded / resolveIfNeeded / resolveAttempts:
        // seem not needed, but don't hurt.
        routeMsg.createVrfIfNeeded = 1
        routeMsg.resolveIfNeeded = 1
        routeMsg.resolveAttempts = 3 // got from sample

        // nextHopWeight is required to be at least 1
        routeMsg.nextHopWeight = 1

        // this seems to be set automatically when you add more than
        // one route to the same destination, not sure
        routeMsg.isMultipath = if (multiplath) {
            1
        } else {
            0
        }

        if (nextHop.isDefined) {
            routeMsg.nextHopAddress = nextHop.get
        }
        if (device.isDefined) {
            routeMsg.nextHopSwIfIndex = device.get
        }
        execVppRequest(routeMsg, lib.ipAddDelRoute)
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
}

