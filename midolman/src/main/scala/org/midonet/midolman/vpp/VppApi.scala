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
          promise.failure(err)
        } else {
          promise.success(result)
        }
      }
    })
    promise.future
  }

  private def vppRequestToFuture[T](request: => CompletionStage[T])
                                   (implicit ec: ExecutionContext): Future[T] = {
    try {
      toScalaFuture[T](request)
    } catch {
      case NonFatal(err) => Future.failed(err)
    }
  }
}

class VppApi(connectionName: String)(implicit ec: ExecutionContext) {

  import VppApi._

  private val registry = new JVppRegistryImpl(connectionName)
  private val coreLib  = new JVppCoreImpl
  private val lib      = new FutureJVppCoreFacade(registry, coreLib)

  // equivalent to:
  // vpp# delete host-interface name <name>
  def deleteDevice(name: String): Future[AfPacketDeleteReply] = {
    val request = new AfPacketDelete
    request.hostIfName = name.toCharArray.map(_.toByte)
    vppRequestToFuture(lib.afPacketDelete(request))
  }

  // equivalent to:
  // vpp# create host-interface name <name>
  def createDevice(name: String,
                   mac: Option[Array[Byte]]): Future[AfPacketCreateReply] = {

    val request = new AfPacketCreate
    request.hostIfName = name.toCharArray.map(_.toByte)
    mac match {
      case Some(addr) =>
        request.hwAddr = addr
        request.useRandomHwAddr = 0
      case None =>
        request.hwAddr = Array[Byte](0, 0, 0, 0, 0, 0) // needed?
        request.useRandomHwAddr = 1
    }
    vppRequestToFuture(lib.afPacketCreate(request))
  }

  // equivalent to:
  // vpp# set int state <interface> up
  def setDeviceAdminState(ifIndex: Int,
                          isUp: Boolean): Future[SwInterfaceSetFlagsReply] = {
    val setUpMsg = new SwInterfaceSetFlags
    setUpMsg.adminUpDown = if (isUp) 1 else 0
    setUpMsg.deleted = 0
    // not 100% sure what linkUpDown=0 means, but it can be seen
    // like this in example code, for example in:
    // https://wiki.fd.io/view/VPP/How_To_Use_The_API_Trace_Tools
    setUpMsg.linkUpDown = 0
    setUpMsg.swIfIndex = ifIndex
    vppRequestToFuture(lib.swInterfaceSetFlags(setUpMsg))
  }

  // equivalent to:
  // vpp# set int ip address <address>/<prefix> <interface>
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
    msg.delAll = if (deleteAll) 1 else 0
    msg.isAdd = if (isAdd) 1 else 0
    msg.isIpv6 = if (isIpv6) 1 else 0
    msg.swIfIndex = ifIndex
    vppRequestToFuture(lib.swInterfaceAddDelAddress(msg))
  }

  //equivalent to:
  // ip route add/del address/prefix via nextHop
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
    routeMsg.isAdd = if (isAdd) 1 else 0
    routeMsg.isIpv6 = if (isIpv6) 1 else 0

    // createVrfIfNeeded / resolveIfNeeded / resolveAttempts:
    // seem not needed, but don't hurt.
    routeMsg.createVrfIfNeeded = 1
    routeMsg.resolveIfNeeded = 1
    routeMsg.resolveAttempts = 3 // got from sample

    // nextHopWeight is required to be at least 1
    routeMsg.nextHopWeight = 1

    // this seems to be set automatically when you add more than
    // one route to the same destination, not sure
    routeMsg.isMultipath = if (multiplath) 1 else 0

    if (nextHop.isDefined) routeMsg.nextHopAddress = nextHop.get
    if (device.isDefined) routeMsg.nextHopSwIfIndex = device.get
    vppRequestToFuture(lib.ipAddDelRoute(routeMsg))
  }
}
