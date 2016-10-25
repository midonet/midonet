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

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.odp.DpPort
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr, MAC}
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}
import org.midonet.util.process.ProcessHelper
import org.midonet.util.process.ProcessHelper.OutputStreams.{StdError, StdOutput}

private object VppSetup extends MidolmanLogging {

    override def logSource = s"org.midonet.vpp-setup"

    trait MacAddressProvider {
        def macAddress: Option[MAC]
    }

    trait VppInterfaceProvider {
        def vppInterface: Option[VppApi.Device]
    }

    class VethPairSetup(override val name: String,
                        devName: String,
                        peerName: String)
                        (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with MacAddressProvider {

        var macAddress: Option[MAC] = None

        @throws[Exception]
        override def execute(): Future[Any] = Future {
            val veth = LinkOps.createVethPair(devName, peerName, up=true)
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
                    macSource: MacAddressProvider)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface: Option[VppApi.Device] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            vppApi.createDevice(deviceName, macSource.macAddress)
                .flatMap { device => {
                              vppInterface = Some(device)
                              vppApi.setDeviceAdminState(device, isUp = true)
                          }}
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            if (vppInterface.isDefined) {
                vppApi.deleteDevice(vppInterface.get)
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
        var dpPort: Option[DpPort] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                dpPort = Some(vppOvs.createDpPort(endpointName))
                Future.successful(Unit)
            } catch {
                case e: Throwable => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            dpPort match {
                case Some(port) => try {
                    vppOvs.deleteDpPort(port)
                    Future.successful(Unit)
                } catch {
                    case e: Throwable => Future.failed(e)
                }
                case _ =>
                    Future.successful(Unit)
            }
        }

        @throws[IllegalStateException]
        def getPortNo: Int = dpPort match {
            case Some(port) => port.getPortNo
            case _ => throw new IllegalStateException(
                s"Datapath Port hasn't been created for $endpointName")
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
                case e: Throwable => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            var firstException: Option[Throwable] = None
            try {
                vppOvs.clearIpv6Flow(ep1fn(), ep2fn())
            } catch {
                case e: Throwable => firstException = Some(e)
            }
            try {
                vppOvs.clearIpv6Flow(ep2fn(), ep1fn())
            } catch {
                case e: Throwable => if (firstException.isEmpty) {
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
                    backEnd: MidonetBackend)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        var oldIfName: Option[String] = None
        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                backEnd.store.tryTransaction(tx =>  {
                    val hostId = HostIdGenerator.getHostId()
                    val oldPort = tx.get(classOf[Port], tenantRouterPortId)
                    oldIfName = Some(oldPort getInterfaceName)
                    val newPort = oldPort.toBuilder
                        .setHostId(toProto(hostId))
                        .setInterfaceName(ovsDownlinkPortName)
                        .build()
                    tx.update(newPort)
                })

                Future.successful(Unit)
            } catch {
                case e: Throwable => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            oldIfName match {
                case Some(ifName) => try {
                    backEnd.store.tryTransaction(tx => {
                        val currentPort = tx.get(classOf[Port], tenantRouterPortId)
                        val restoredPort = currentPort.toBuilder
                            .setInterfaceName(ifName)
                            .build()
                        tx.update(restoredPort)
                    })
                    Future.successful(Unit)
                } catch {
                    case e: Throwable => Future.failed(e)
                }
                case _ =>
                    Future.successful(Unit)
            }
        }
    }
}

import VppSetup._

class VppSetup(setupName: String)
                       (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback(setupName)(ec)

class VppUplinkSetup(uplinkPortId: UUID,
                     uplinkPortDpNo: Int,
                     vppApi: VppApi,
                     vppOvs: VppOvs)
                     (implicit ec: ExecutionContext)
    extends VppSetup("VPP uplink setup") {
    
    private val uplinkSuffix = uplinkPortId.toString.substring(0, 8)
    private val uplinkVppName = s"vpp-$uplinkSuffix"
    private val uplinkOvsName = s"ovs-$uplinkSuffix"
    private val uplinkVppAddr = IPv6Addr.fromString("2001::1")

    private val uplinkVppPrefix: Byte = 64

    private val uplinkVeth = new VethPairSetup("uplink veth pair",
                                               uplinkVppName,
                                               uplinkOvsName)

    private val uplinkVpp = new VppDevice("uplink device at vpp",
                                          uplinkVppName,
                                          vppApi,
                                          uplinkVeth)

    private val ipAddrVpp = new VppIpAddr("uplink IPv6 address at vpp",
                                          vppApi,
                                          uplinkVpp,
                                          uplinkVppAddr,
                                          uplinkVppPrefix)
    private val ovsBind = new OvsBindV6("ovs bind for vpp uplink veth",
                                        vppOvs,
                                        uplinkOvsName)
    private val vppFlows = new FlowInstall("install ipv6 flows",
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
    add(vppFlows)
}

/**
  * @param downlinkPortId port id of the tenant router port that has IP6
  *                              address associated
  * @param vppApi handler for the JVPP
  * @param ec Execution context for futures
  */
class VppDownlinkSetup(downlinkPortId: UUID,
                       vrf: Int,
                       vppApi: VppApi,
                       backEnd: MidonetBackend)
                       (implicit ec: ExecutionContext)
    extends VppSetup("VPP downlink setup")(ec) {

    private val dlinkSuffix = downlinkPortId.toString.substring(0, 8)
    private val dlinkVppName = s"vpp-$dlinkSuffix"
    private val dlinkOvsName = s"ovs-$dlinkSuffix"
    private val dlinkVppAddr = IPv4Addr.fromString("10.0.0.2")

    private val dlinkVppPrefix: Byte = 24

    private val dlinkVeth = new VethPairSetup("dlink veth pair",
                                              dlinkVppName,
                                              dlinkOvsName)

    private val dlinkVpp = new VppDevice("dlink device at vpp",
                                          dlinkVppName,
                                          vppApi,
                                          dlinkVeth)

    private val ipAddrVpp = new VppIpAddr("dlink IP4 address at vpp",
                                          vppApi,
                                          dlinkVpp,
                                          dlinkVppAddr,
                                          dlinkVppPrefix)

    private val ovsBind = new OvsBindV4("dlink bind veth pair to the tenant router",
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
